package com.tailf.packages.ned.asa;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.io.BufferedWriter;
import java.io.BufferedReader;

import java.util.HashMap;
import java.util.concurrent.Semaphore;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SCPInputStream;
import ch.ethz.ssh2.SCPOutputStream;
import ch.ethz.ssh2.channel.ChannelClosedException;
import ch.ethz.ssh2.Session;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

/*
 * SCPSession
 *
 * Utility class for SCP transfer using a pool of thread safe SSH connections.
 *
 * @author lbang
 */
@SuppressWarnings("deprecation")
class SCPSession {

    private static final String EXEC_PROMPT = "\\A[^\\# ]+#[ ]?$";

    // Static data
    private static HashMap<String, SCPSession> instances = new HashMap<>();
    private int numInstances = 0;
    private int active = 0;

    // Constructor data
    private String deviceName;
    private String address;
    private String port;
    private String ruser;
    private String pass;

    // nedSettings
    private int retries;
    private int waitTime;
    private int maxSessions;
    private long cliKeepaliveInterval;

    // Private data
    private Semaphore semaphore;


    /**
     * Constructor
     * @param
     * @throws Exception
     */
    private SCPSession(ASANedCli owner, NedWorker worker, String deviceName,
                       String address, String port, String ruser, String pass) throws Exception {

        this.deviceName = deviceName;
        this.address = address;
        this.port = port;
        this.ruser = ruser;
        this.pass = pass;

        this.retries = owner.nedSettings.getInt("scp-transfer/number-of-retries");
        this.waitTime = owner.nedSettings.getInt("scp-transfer/time-between-retry");
        this.maxSessions = owner.nedSettings.getInt("scp-transfer/max-sessions");
        this.cliKeepaliveInterval = owner.nedSettings.getInt("scp-transfer/cli-keepalive-interval") * 1000;

        this.semaphore = new Semaphore(this.maxSessions, true);
        myTraceInfo(owner, worker, "instance created [max-sessions "+maxSessions+"]");
        myTraceVerbose(owner, worker, "semaphore permits = "+this.semaphore.availablePermits());

        if (owner.logVerbose) {
            ch.ethz.ssh2.log.Logger.enabled = true;
        }
    }


    /**
     * Get Instance
     * @param
     * @return
     * @throws Exception
     */
    public static SCPSession getInstance(ASANedCli owner, NedWorker worker, String deviceName,
                                         String address, String port, String ruser, String pass) throws Exception {

        SCPSession instance;
        int num;
        synchronized (instances) {
            instance = instances.get(deviceName+address);
            if (instance == null) {
                instance = new SCPSession(owner, worker, deviceName, address, port, ruser, pass);
                instances.put(deviceName+address, instance);
            }
            num = ++instance.numInstances;
        }

        // Note: non-static myTraceInfo can't be called from static context
        owner.logInfo(worker, "SCP "+deviceName+" "+address+" instance opened ["+num+"]");

        return instance;
    }


    /**
     * Close Instance
     * @param
     * @throws Exception
     */
    public void closeInstance(ASANedCli owner, NedWorker worker, String address) throws Exception {
        SCPSession instance;
        int num;
        synchronized (instances) {
            instance = instances.get(deviceName+address);
            num = --numInstances;
            if (num == 0) {
                instances.remove(deviceName+address);
            }
        }
        if (instance == null) {
            throw new NedException("Internal ERROR: missing instance for "+deviceName);
        }

        // Close all SCP workers
        myLogInfo(owner, worker, address+" instance closed ["+num+"]");
    }


    /**
     * Put config on device
     * @param
     * @throws Exception
     */
    public void put(ASANedCli owner, NedWorker worker, String config, String file, String dir) throws Exception {

        // Lock
        lock(owner, worker, "put");

        Connection conn = null;
        SCPOutputStream out = null;
        final long start = tick(0);
        try {
            // Open connection
            conn = openConnection(owner, worker);

            // Upload the config
            myTraceInfo(owner, worker, "uploading "+config.length()+" bytes to "+dir+file);
            out = upload(owner, worker, conn, config, file, dir);
            myTraceInfo(owner, worker, "uploaded "+config.length()+" bytes"+msToString(start));

        } finally {
            // Close connection
            closeConnection(owner, worker, conn, out, null);

            // Unlock
            unlock(owner, worker, "put");
        }
    }


    /**
     * Get config from device
     * @param
     * @return
     * @throws Exception
     */
    public String get(ASANedCli owner, NedWorker worker, String file, String dir) throws Exception {

        // Lock
        lock(owner, worker, "get");

        Connection conn = null;
        SCPInputStream in = null;
        BufferedReader reader = null;
        final long start = tick(0);
        try {
            // Open connection
            conn = openConnection(owner, worker);

            // Download the config
            myTraceInfo(owner, worker, "downloading "+dir+file);
            ASASCPClient client = new ASASCPClient(conn);
            Session session = conn.openSession();
            session.execCommand("scp -f " + dir+file);
            in = new SCPInputStream(client, session);
            reader = new BufferedReader(new InputStreamReader(in));
            String line;
            StringBuilder sb = new StringBuilder();
            long lastSetTimeout = tick(0);
            while ((line = reader.readLine()) != null) {
                sb.append(line+"\r\n");

                // Update download timeout
                long time = tick(0);
                if ((time - lastSetTimeout) >= (0.5 * owner.readTimeout)) {
                    lastSetTimeout = time;
                    setTimeout(owner, worker, owner.readTimeout, " [read "+sb.length()+"]");
                }
            }
            String config = sb.toString();
            myTraceInfo(owner, worker, "downloaded "+config.length()+" bytes"+msToString(start));
            return config;

        } finally {
            // Close reader
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception e) {
                    myTraceInfo(owner, worker, "download Exception: "+e.getMessage());
                }
            }

            // Close connection (session closed by SCPInputStream.close()
            closeConnection(owner, worker, conn, null, in);

            // Unlock
            unlock(owner, worker, "get");
        }
    }


    /**
     * Lock
     * @param
     * @throws Exception
     */
    private void lock(ASANedCli owner, NedWorker worker, String whom) throws Exception {
        semaphore.acquire();
        myTraceVerbose(owner, worker, "LOCKING");
        this.active++;
        myTraceInfo(owner, worker, "begin "+whom+" [active "+this.active+"]");
    }


    /**
     * Unlock
     * @param
     */
    private void unlock(ASANedCli owner, NedWorker worker, String whom) {
        this.active--;
        myTraceInfo(owner, worker, "done "+whom+" [active "+this.active+"]");
        myTraceVerbose(owner, worker, "UNLOCKING ["+semaphore.availablePermits()+"]");
        semaphore.release();
    }


    /**
     * Open SCP connection
     * @param
     * @return
     * @throws Exception
     */
    private Connection openConnection(ASANedCli owner, NedWorker worker) throws Exception {

        //
        // SSH connect [optionally retry several times, waiting for SCP server session]
        //
        Connection conn = new Connection(address, Integer.parseInt(port));
        for (int i = retries; i >= 0; i--) {
            myTraceInfo(owner, worker, "connecting to host: "+address+":"+port
                      +" ["+(1+retries-i)+"/"+(retries+1)+"] timeout = "+owner.connectTimeout);
            try {
                conn.connect(null, 0, owner.connectTimeout);
                myTraceVerbose(owner, worker, "session connected");
                break;
            } catch (Exception e) {
                String failmsg = "connect :: "+e.getMessage();
                if (i > 0) {
                    // Got more retries -> sleep and retry
                    myTraceInfo(owner, worker, failmsg + " - retrying #"+(1+retries-i));
                    worker.setTimeout(owner.connectTimeout + waitTime * 1000);
                    sleep(owner, worker, waitTime * (long)1000);
                } else {
                    // Out of retries -> throw NedException
                    throw new NedException("SCP open "+deviceName+" "+failmsg);
                }
            }
        }

        //
        // Authenticate with password
        //
        conn.authenticateWithPassword(ruser, pass);
        if (!conn.isAuthenticationComplete()) {
            conn.close();
            throw new NedException("SCP open "+deviceName+" authentication failed");
        }
        myTraceVerbose(owner, worker, "authenticated");

        setTimeout(owner, worker, owner.readTimeout, null);

        return conn;
    }


    /**
     * Close SCP connection
     * @param
     */
    private void closeConnection(ASANedCli owner, NedWorker worker,
                                 Connection conn, SCPOutputStream out, SCPInputStream in) {

        // Note: 'out' must NEVER be closed before 'conn' or server will get stale connections

        // Close connection
        if (conn != null) {
            myTraceInfo(owner, worker, "closing connection to: "+conn.getHostname()+":"+conn.getPort());
            try {
                conn.close();
            } catch (Exception e) {
                myLogError(owner, worker, "connection close ERROR: "+e.getMessage(), e);
            }
        }

        // Close SCPOutputStream (and Session)
        if (out != null) {
            myTraceVerbose(owner, worker, "closing output stream");
            try {
                out.close();
            } catch (ChannelClosedException ignore) {
                // ignore
            } catch (IOException e) {
                myLogError(owner, worker, "output stream close ERROR: "+e.getMessage(), e);
            }
        }

        // Close SCPInputStream (and Session)
        if (in != null) {
            myTraceVerbose(owner, worker, "closing input stream");
            try {
                in.close();
            } catch (ChannelClosedException ignore) {
                // ignore
            } catch (IOException e) {
                myLogError(owner, worker, "input stream close ERROR: "+e.getMessage(), e);
            }
        }
    }


    /**
     * Upload config
     * @param
     * @return
     * @throws Exception
     */
    private SCPOutputStream upload(ASANedCli owner, NedWorker worker, Connection conn,
                                   String config, String file, String dir) throws Exception {

        ASASCPClient client = null;
        SCPOutputStream out = null;
        BufferedWriter writer = null;
        try {
            client = new ASASCPClient(conn);
            out = client.put(file, config.length(), dir, "0644");
            writer = new BufferedWriter(new OutputStreamWriter(out));

            final int CHUNK_SIZE = 8192;
            int offset = 0;
            int length = config.length();
            long lastKeepalive = tick(0);
            long lastSetTimeout = lastKeepalive;
            do {
                // Write chunk
                int size = Math.min(CHUNK_SIZE, length);
                writer.write(config, offset, size);
                offset += size;
                length -= size;

                // Update upload timeout
                long time = tick(0);
                if ((time - lastSetTimeout) >= (0.5 * owner.readTimeout)) {
                    lastSetTimeout = time;
                    setTimeout(owner, worker, owner.readTimeout,
                               " [written "+(config.length()-length)+"/"+config.length()+"]");
                }

                // Keep CLI SSH session alive
                if (this.cliKeepaliveInterval > 0 && (time - lastKeepalive) > this.cliKeepaliveInterval) {
                    lastKeepalive = time;
                    if (owner.session.serverSideClosed()) {
                        myLogError(owner, worker, "CLI session closed during SCP upload "
                                   +" [written "+(config.length()-length)+"/"+config.length()+"]", null);
                    } else {
                        myTraceInfo(owner, worker, "Sending CLI keepalive to "+owner.device_id);
                        owner.session.println("");
                        owner.session.expect(new String[] { EXEC_PROMPT }, worker);
                        myTraceInfo(owner, worker, owner.device_id + " CLI is alive");
                    }
                }
            } while (length > 0);

        } finally {
            if (writer != null) {
                try {
                    writer.close();
                    writer = null;
                } catch (Exception e) {
                    myTraceInfo(owner, worker, "upload Exception: "+e.getMessage());
                }
            }
            client = null;
        }
        return out;
    }


    /**
     * Reset timeout, ignore but log any Exceptions
     */
    private void setTimeout(ASANedCli owner, NedWorker worker, int ms, String info) {
        try {
            String log = "timeout reset to "+ms+" ms";
            if (info != null) {
                log += info;
            }
            myTraceInfo(owner, worker, log);
            worker.setTimeout(ms);
        } catch (Exception e) {
            myLogInfo(owner, worker, "WARNING: Ignoring Exception when reset timeout");
        }
    }


    /**
     * Sleep ms milliseconds
     * @param
     */
    private void sleep(ASANedCli owner, NedWorker worker, long ms) {
        myTraceVerbose(owner, worker, "Sleeping "+ms+" milliseconds");
        try {
            Thread.sleep(ms);
            myTraceVerbose(owner, worker, "Woke up from sleep");
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Log error
     * @param
     */
    private void myLogError(ASANedCli owner, NedWorker worker, String text, Exception e) {
        owner.logError(worker, "SCP " + deviceName + " " + text, e);
    }


    /**
     * Log info
     * @param
     */
    private void myLogInfo(ASANedCli owner, NedWorker worker, String text) {
        owner.logInfo(worker, "SCP " + deviceName + " " + text);
    }


    /**
     * Trace info
     * @param
     */
    private void myTraceInfo(ASANedCli owner, NedWorker worker, String text) {
        owner.traceInfo(worker, "SCP " + deviceName + " " + text);
    }


    /**
     * Trace verbose
     * @param
     */
    private void myTraceVerbose(ASANedCli owner, NedWorker worker, String text) {
        if (!owner.logVerbose) {
            return;
        }
        owner.traceInfo(worker, "SCP " + deviceName + " " + text);
    }


    /**
     * Simple tick utility used for performance meassurements.
     * @param
     */
    private long tick(long t) {
        return System.currentTimeMillis() - t;
    }


    /**
     * Format passed time in milliseconds
     * @param
     * @return
     */
    protected String msToString(long start) {
        long stop = tick(start);
        return String.format(" in %d milliseconds", stop);
    }

}
