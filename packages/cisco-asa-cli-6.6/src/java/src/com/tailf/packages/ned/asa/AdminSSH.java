
package com.tailf.packages.ned.asa;

import java.io.IOException;

import java.util.regex.Pattern;
import java.util.Queue;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.tailf.ned.SSHSession;
import com.tailf.ned.SSHSessionException;

import ch.ethz.ssh2.Connection;

import org.apache.log4j.Logger;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;

/*
 * AdminSSH
 */
@SuppressWarnings("deprecation")
class AdminSSH {

    // Static data
    private static HashMap<String, AdminSSH> instances = new HashMap<>();
    private static Logger logger = Logger.getLogger(ASANedCli.class);

    // Constructor data
    private String adminDeviceName;
    private boolean logVerbose;
    private String address;
    private String port;
    private String ruser;
    private String pass;
    private String secpass;
    private int connectTimeout;
    private int retries;
    private int waitTime;
    private int maxSessions;

    // Private data
    private int numInstances = 0;
    private int nextSessionId = 0;
    private AtomicInteger openSessions = new AtomicInteger();
    private Queue<Session> sessions = new LinkedList<>();
    private DynSemaphore semaphore;

    // Static final data
    private final static String PRIVEXEC_PROMPT = "\\A[^\\# ]+#[ ]?$";
    private final static String PROMPT = "\\A\\S*#";

    /*
     * Constructor
     */
    private AdminSSH(NedWorker worker, String device_id, String adminDeviceName, boolean logVerbose,
                            String address, String port, String ruser, String pass, String secpass,
                            int connectTimeout, int retries, int waitTime, int maxSessions)
    {
        this.adminDeviceName = adminDeviceName;
        this.logVerbose = logVerbose;
        this.address = address;
        this.port = port;
        this.ruser = ruser;
        this.pass = pass;
        this.secpass = secpass;
        this.connectTimeout = connectTimeout;
        this.retries = retries;
        this.waitTime = waitTime;
        this.maxSessions = maxSessions;

        this.semaphore = new DynSemaphore();
        logInfo(worker, device_id, -1, "class created, max-sessions = "+maxSessions);
        logVerbose(worker, device_id, -1, "semaphore permits = "+this.semaphore.availablePermits());
    }

    /*
     * logError
     */
    private void logError(NedWorker worker, String device_id, String text) {
        logger.error(device_id + " " + text);
        worker.trace("-- " + text + "\n", "out", device_id);
    }

    /*
     * logInfo
     */
    private void logInfo(NedWorker worker, String device_id, int id, String text) {
        String log = "admin-device: SSH";
        if (id != -1) {
            log += "["+id+"]";
        }
        log += (" " + adminDeviceName + " " + text);
        logger.info(device_id + " " + log);
        worker.trace("-- " + log + "\n", "out", device_id);
    }

    /*
     * logVerbose
     */
    private void logVerbose(NedWorker worker, String device_id, int id, String text) {
        if (logVerbose == false)
            return;
        String log = "admin-device: SSH";
        if (id != -1) {
            log += "["+id+"]";
        }
        log += (" " + adminDeviceName + " " + text);
        logger.debug(device_id + " " + log);
        if (worker != null) {
            worker.trace("-- " + log + "\n", "out", device_id);
        }
    }

    /*
     * Class Session
     */
    private class Session {

        // Local data
        private int id;
        private Connection adminConn = null;
        private NewSSHSession adminSession = null;

        // Session.Constructor
        Session(int id)
            throws Exception {
            this.id = id;
        }

        // Session.open
        public void open(NedWorker worker, String device_id)
            throws Exception {

            if (adminSession != null) {
                return;
            }

            //
            // SSH connect [optionally retry several times, waiting for available session]
            //
            for (int i = retries; i >= 0; i--) {
                logInfo(worker, device_id, id, "connecting to host: "+address+":"+port
                        +" ["+(1+retries-i)+"/"+(retries+1)+"]");
                try {
                    adminConn = new Connection(address, Integer.parseInt(port));
                    adminConn.connect(null, 0, connectTimeout);
                    logVerbose(worker, device_id, id, "session connected");
                    break;
                }
                catch (Exception e) {
                    String failmsg = "connect :: "+e.getMessage();
                    adminConn = null;
                    if (i > 0) {
                        // Got more retries -> sleep and retry
                        logVerbose(worker, device_id, id, failmsg + " - retrying");
                        worker.setTimeout(connectTimeout + waitTime * 1000);
                        sleep(worker, device_id, waitTime * (long)1000);
                    }
                    else {
                        // Out of retries -> throw exception
                        throw new Exception("admin-device: SSH["+id+"] "+adminDeviceName+" "+failmsg);
                    }
                }
            }

            //
            // Authenticate with password
            //
            adminConn.authenticateWithPassword(ruser, pass);
            if (adminConn.isAuthenticationComplete() == false) {
                throw new Exception("admin-device: SSH["+id+"] "+adminDeviceName+" authentication failed");
            }
            logVerbose(worker, device_id, id, "authenticated");

            try {
                // Create SSHSession
                adminSession = new NewSSHSession(adminConn, connectTimeout, worker, device_id, 200, 24);

                // Enable device
                NedExpectResult res = adminSession.expect(new String[] {
                        "\\A[Ll]ogin:",
                        "\\A[Uu]sername:",
                        "\\A[Pp]assword:",
                        "\\A\\S.*>",
                        PRIVEXEC_PROMPT}, worker);
                if (res.getHit() < 3) {
                    throw new NedException("Authentication failed");
                }
                if (res.getHit() == 3) {
                    adminSession.print("enable\n");
                    res = adminSession.expect(new String[] {"[Pp]assword:", PROMPT}, worker);
                    if (res.getHit() == 0) {
                        if (secpass == null || secpass.isEmpty()) {
                            secpass = "";
                        }
                        logVerbose(worker, device_id, id, "Sending enable password (NOT LOGGED)");
                        adminSession.setTracer(null);
                        adminSession.print(secpass+"\n"); // enter password here
                        adminSession.setTracer(worker);
                        try {
                            res = adminSession.expect(new String[] {"\\A\\S*>", PROMPT}, worker);
                            if (res.getHit() == 0) {
                                throw new NedException("admin-device: SSH["+id+"] "+adminDeviceName+" Secondary password authentication failed");
                            }
                        } catch (Exception e) {
                            throw new NedException("admin-device: SSH["+id+"] "+adminDeviceName+" Secondary password authentication failed");
                        }
                    }
                }

                // Set pager
                print_line_exec(worker, "terminal pager 0");

                // Show version to verify it's an ASA device
                String version = print_line_exec(worker, "show version");
                if (!version.contains("Cisco Adaptive Security Appliance")) {
                    throw new Exception("admin-device: SSH["+id+"] "+adminDeviceName+" unknown device");
                }

                // Change to system context
                changeto_system(worker);

                // Success
                int num = openSessions.getAndIncrement() + 1;
                logInfo(worker, device_id, id, "session opened ["+num+"]");
                if (num < maxSessions) {
                    semaphoreIncrease(worker, device_id, num + 1);
                }
            } catch (Exception e) {
                adminSession = null;
                throw e;
            }
            finally {
                if (adminSession != null) {
                    adminSession.setTracer(null);
                }
            }
        }

        // Session.close
        public void close(NedWorker worker, String device_id)
            throws Exception {
            try {
                // Close open session
                if (adminSession != null) {
                    adminSession.setTracer(worker);

                    // Send quit to logout from shell
                    try {
                        print_line_exec(worker, "quit");
                    } catch (Exception ignore) {
                        // Ignore Exception
                    }

                    // SSH close
                    try {
                        adminConn.close();
                        adminSession.setTracer(null);
                        adminSession.close();
                    } catch (Exception ignore) {
                        // Ignore Exception
                    }

                    // Decrease open sessions and semaphore count
                    int num = openSessions.getAndDecrement();
                    logInfo(worker, device_id, id, "session closed ["+num+"]");
                    if (num > 1) {
                        semaphoreReduce(worker, device_id, num - 1);
                    }
                }
            }
            finally {
                // Delete session
                adminConn = null;
                adminSession = null;
                logInfo(worker, device_id, id, "session deleted");
            }
        }

        // Session.print_line_exec
        private String print_line_exec(NedWorker worker, String line)
            throws IOException, SSHSessionException {

            // Send command and wait for echo
            adminSession.print(line + "\n");
            adminSession.expect(new String[] { Pattern.quote(line) }, worker);

            // Return device output
            return adminSession.expect(PRIVEXEC_PROMPT, worker);
        }

        /*
         * Session.print_line_wait
         */
        public String print_line_wait(NedWorker worker, String device_id, String line)
            throws Exception {

            adminSession.setTracer(worker);
            logInfo(worker, device_id, id, "SENDING: '"+line+"'");
            String res = print_line_exec(worker, line);
            adminSession.setTracer(null);

            return res;
        }

        /*
         * Session.changeto_system
         */
        private void changeto_system(NedWorker worker)
            throws NedException, IOException, SSHSessionException {

            // Send changeto command and wait for echo
            adminSession.print("changeto system\n");
            adminSession.expect("changeto system", worker);

            // Wait for prompt
            String reply = adminSession.expect(PRIVEXEC_PROMPT, worker);
            if (reply.contains("ERROR: ") || reply.contains("Command not valid ")) {
                throw new NedException("Failed to changeto system context");
            }
        }

        /*
         * Session.sleep
         */
        private void sleep(NedWorker worker, String device_id, long ms) {
            logVerbose(worker, device_id, id, "Sleeping "+ms+" milliseconds");
            try {
                Thread.sleep(ms);
                logVerbose(worker, device_id, id, "Woke up from sleep");
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /*
     * semaphoreIncrease
     */
    public void semaphoreIncrease(NedWorker worker, String device_id, int num)
        throws InterruptedException {
        logVerbose(worker, device_id, -1, "semaphore permits increased to "+num);
        semaphore.increasePermits(1);
    }

    /*
     * semaphoreReduce
     */
    public void semaphoreReduce(NedWorker worker, String device_id, int num)
        throws InterruptedException {
        semaphore.reducePermits(1);
        logVerbose(worker, device_id, -1, "semaphore permits decreased to "+num);
    }

    /*
     * getInstance
     */
    public static AdminSSH getInstance(NedWorker worker, String device_id, String adminDeviceName, boolean logVerbose,
                                              String address, String port, String ruser, String pass, String secpass,
                                              int connectTimeout, int retries, int waitTime, int maxSessions)
        throws Exception {

        AdminSSH instance;
        int num;
        synchronized (instances) {
            instance = instances.get(adminDeviceName);
            if (instance == null) {
                instance = new AdminSSH(worker, device_id,adminDeviceName,logVerbose,
                                               address,port,ruser,pass,secpass,
                                               connectTimeout,retries,waitTime,maxSessions);
                instances.put(adminDeviceName, instance);
            }
            num = ++instance.numInstances;
        }

        String text = "admin-device: SSH "+adminDeviceName+" instance opened ["+num+"]";
        logger.info(device_id + " " + text);
        worker.trace("-- " + text + "\n", "out", device_id);

        return instance;
    }

    /*
     * closeInstance
     */
    public void closeInstance(NedWorker worker, String device_id)
        throws Exception {

        AdminSSH instance;
        int num;
        synchronized (instances) {
            instance = instances.get(adminDeviceName);
            num = --numInstances;
            if (num == 0) {
                instances.remove(adminDeviceName);
            }
        }
        if (instance == null) {
            throw new Exception("Internal ERROR: missing instance for "+adminDeviceName);
        }

        // Close all sessions
        logInfo(worker, device_id, -1, "instance closed ["+num+"]");
        if (num == 0) {
            while (sessions.peek() != null) {
                Session session = sessions.remove();
                session.close(worker, device_id);
            }
        }
    }

    /*
     * print_line_wait
     */
    public String print_line_wait(NedWorker worker, String device_id, String line)
        throws Exception {
        String res = "";

        for (int loops = 1;;loops++) {
            try {
                semaphore.acquire();
                logVerbose(worker, device_id, -1, "LOCKED");

                // Get session
                Session session;
                synchronized (sessions) {
                    session = sessions.poll();
                    if (session != null) {
                        // Reuse old session
                        logInfo(worker, device_id, session.id, "session reused");
                    }
                    else {
                        // Create new session
                        session = new Session(++nextSessionId);
                        logInfo(worker, device_id, session.id, "session created");
                    }
                }

                // Communicate with the admin-advice
                try {
                    // Open connection (if needed)
                    session.open(worker, device_id);

                    // Send the command
                    res = session.print_line_wait(worker, device_id, line);

                } catch (Exception e) {
                    int num = openSessions.get();
                    if (num > 0) {
                        // Got at least one open session, keep retrying to eventually reuse it
                        logVerbose(worker, device_id, session.id, "session failed, retrying #"+loops+" ["+num+"]");
                        continue;
                    }
                    // Failure, delete this session
                    session.close(worker, device_id);
                    session = null;
                    throw e;
                }

                // Return the session for next request
                synchronized (sessions) {
                    sessions.add(session);
                    logVerbose(worker, device_id, session.id, "session returned");
                }

                // Done
                break;

            } catch (Exception e) {
                logError(worker, device_id, e.getMessage());
                throw e;
            }
            finally {
                logVerbose(worker, device_id, -1, "UNLOCKED ["+semaphore.availablePermits()+"]");
                semaphore.release();
            }
        } // for(;;)

        return res;
    }
}
