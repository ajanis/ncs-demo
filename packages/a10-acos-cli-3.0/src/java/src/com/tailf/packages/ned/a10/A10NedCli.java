package com.tailf.packages.ned.a10;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import com.tailf.conf.ConfPath;
import com.tailf.ned.NedCapability;
import com.tailf.ned.NedCliBase;
import com.tailf.ned.NedCliBaseTemplate;
import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSessionException;


/**
 * This class implements NED interface for cisco ios routers
 *
 */

public class A10NedCli extends NedCliBaseTemplate  {

    private String date_string = "2017-02-28";
    private String version_string = "3.0.2.1";
    private static String prompt = "\\A\\S*#";
    private static Pattern prompt_pattern;
    private static Pattern[]
        move_to_config_pattern,
        print_line_wait_pattern,
        print_line_wait_confirm_pattern,
        enter_config_pattern,
        enter_config_pattern2,
        exit_config_pattern;

    static {
        prompt_pattern = Pattern.compile(prompt);

        move_to_config_pattern = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(config.*\\)#")
        };

        print_line_wait_pattern = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            prompt_pattern
        };

        print_line_wait_confirm_pattern = new Pattern[] {
            Pattern.compile("Are you sure"),
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            prompt_pattern
        };

        enter_config_pattern = new Pattern[] {
            Pattern.compile("Do you want to kill that session and continue"),
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

        enter_config_pattern2 = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

        exit_config_pattern = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            prompt_pattern
        };
    }

    public A10NedCli() {
        super();
    }

    public A10NedCli(String device_id,
               InetAddress ip,
               int port,
               String proto,  // ssh or telnet
               String ruser,
               String pass,
               String secpass,
               boolean trace,
               int connectTimeout, // msec
               int readTimeout,    // msec
               int writeTimeout,
               NedMux mux,
               NedWorker worker) {

        super(device_id, ip, port, proto, ruser, pass, secpass,
              trace, connectTimeout, readTimeout, writeTimeout, mux,
              worker);

        NedExpectResult res;

        try {
            try {
                if (proto.equals("ssh")) {
                    setupSSH(worker);
                }
                else {
                    setupTelnet(worker);
                }
            }
            catch (Exception e) {
                LOGGER.error("connect failed ",  e);
                worker.connectError(NedWorker.CONNECT_CONNECTION_REFUSED,
                                    e.getMessage());
                return;
            }
        }
        catch (NedException e) {
            LOGGER.error("connect response failed ",  e);
            return;
        }

        try {
            res = session.expect(new String[] {
                    "\\A[Ll]ogin:", "\\A[Uu]sername:",
                    "\\A.*>", prompt}, worker);
            if (res.getHit() < 2)
                throw new NedException("Authentication failed");
            if (res.getHit() == 2) {
                session.print("enable\n");
                res = session.expect(new String[] {"[Pp]assword:", prompt},
                                     worker);
                if (res.getHit() == 0) {
                    trace(worker, "Secondary password requested", "out");
                    session.print(secpass+"\n"); // enter password here
                    try {
                        res = session.expect(new Pattern[]
                            {Pattern.compile("\\A.*>"), prompt_pattern},
                                             worker);
                        if (res.getHit() == 0)
                            throw new NedException("Secondary password "
                                                   +"authentication failed");
                    } catch (Exception e) {
                        throw new NedException("Secondary password "
                                               +"authentication failed");
                    }
                }
            }
            trace(worker, "Initializing terminal", "out");
            session.print("terminal length 0\n");
            session.expect(prompt_pattern, worker);
            session.print("terminal width 0\n");
            session.expect(prompt_pattern, worker);

            trace(worker, "Requesting version string", "out");
            session.print("show version\n");
            String version = session.expect(prompt_pattern, worker);

            try {
                trace(worker, "Inspecting version string", "out");
                /* look for version string */
                if (version.indexOf("AX Series Advanced Traffic") >= 0) {

                    // found A10
                    NedCapability capas[] = new NedCapability[1];
                    NedCapability statscapas[] = new NedCapability[0];

                    capas[0] = new NedCapability(
                                                 "",
                                  "http://tail-f.com/ned/a10-acos",
                                                 "tailf-ned-a10-acos",
                                                 "",
                                                 date_string,
                                                 "");

                    setConnectionData(capas,
                                      statscapas,
                                      true,
                                      TransactionIdMode.NONE);
                    trace(worker, "NED VERSION: a10-acos "+
                          version_string+" "+date_string, "out");

                } else {
                    trace(worker, "Did not find expected version string",
                          "out");
                    worker.error(NedCmd.CONNECT_CLI,
                                 "unknown device, unknown version string");
                }
            } catch (Exception e) {
                new NedException("Failed to read device version string");
            }
        }
        catch (SSHSessionException e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
        catch (IOException e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
        catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI, e.getMessage());
        }
    }

    public void reconnect(NedWorker worker) {
        // all capas and transmode already set in constructor
        // nothing needs to be done
    }

    // Which Yang modules are covered by the class
    public String [] modules() {
        return new String[] { "tailf-ned-a10-acos" };
    }

    // Which identity is implemented by the class
    public String identity() {
        return "a10-acos-id:a10-acos";
    }

    private void moveToTopConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(move_to_config_pattern);
            if (res.getHit() == 0)
                return;
        }
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) });
        res = session.expect(print_line_wait_pattern);

        if (res.getHit() == 0)
            isAtTop = true;
        else if (res.getHit() == 1)
            isAtTop = false;
        else
            throw new ApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].toLowerCase().indexOf("error") >= 0 ||
                lines[i].toLowerCase().indexOf("exceeded") >= 0 ||
                lines[i].toLowerCase().indexOf("invalid") >= 0 ||
                lines[i].toLowerCase().indexOf("incomplete") >= 0 ||
                lines[i].toLowerCase().indexOf("duplicate name") >= 0 ||
                lines[i].toLowerCase().indexOf("failed") >= 0) {
                throw new ApplyException(line, lines[i], isAtTop, true);
            }
            if (lines[i].toLowerCase().indexOf("is in use") >= 0 ||
                lines[i].toLowerCase().indexOf("already exists") >= 0) {
                // wait a while and retry
                if (retrying > 60) {
                    // already tried enough, give up
                    throw new ApplyException(line, lines[i], isAtTop, true);
                }
                else {
                    if (retrying == 0)
                        worker.setTimeout(10*60);
                    // sleep a second
                    try { Thread.sleep(1*1000);
                    } catch (InterruptedException e) {
                        System.err.println("sleep interrupted");
                    }
                    return print_line_wait(worker, cmd, line, retrying+1);
                }
            }
        }

        return isAtTop;
    }

    private boolean print_line_wait_confirm(NedWorker worker, int cmd,
                                            String line,
                                            int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) });
        res = session.expect(print_line_wait_confirm_pattern);

        if (res.getHit() == 0)
            return print_line_wait(worker, cmd, "y", 0);
        else if (res.getHit() == 1)
            isAtTop = true;
        else if (res.getHit() == 2)
            isAtTop = false;
        else
            throw new ApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].toLowerCase().indexOf("error") >= 0 ||
                lines[i].toLowerCase().indexOf("exceeded") >= 0 ||
                lines[i].toLowerCase().indexOf("invalid") >= 0 ||
                lines[i].toLowerCase().indexOf("incomplete") >= 0 ||
                lines[i].toLowerCase().indexOf("duplicate name") >= 0 ||
                lines[i].toLowerCase().indexOf("failed") >= 0) {
                throw new ApplyException(line, lines[i], isAtTop, true);
            }
            if (lines[i].toLowerCase().indexOf("is in use") >= 0 ||
                lines[i].toLowerCase().indexOf("already exists") >= 0) {
                // wait a while and retry
                if (retrying > 60) {
                    // already tried enough, give up
                    throw new ApplyException(line, lines[i], isAtTop, true);
                }
                else {
                    if (retrying == 0)
                        worker.setTimeout(10*60);

                    // sleep a second
                    try { Thread.sleep(1*1000);
                    } catch (InterruptedException e) {
                        System.err.println("sleep interrupted");
                    }
                    return print_line_wait_confirm(worker, cmd,
                                                   line, retrying+1);
                }
            }
        }

        return isAtTop;
    }

    private void print_line_wait_oper(NedWorker worker, int cmd,
                                      String line)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) });
        res = session.expect(new Pattern[] {prompt_pattern});

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].toLowerCase().indexOf("error") >= 0 ||
                lines[i].toLowerCase().indexOf("failed") >= 0) {
                throw new ApplyException(line, lines[i], true, false);
            }
        }
    }

    private boolean enterConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException {
        NedExpectResult res = null;

        session.print("config t\n");
        res = session.expect(enter_config_pattern);
        if (res.getHit() > 2) {
            worker.error(cmd, res.getText());
            return false;
        } else if (res.getHit() == 0) {
            session.print("yes\n");
            res = session.expect(enter_config_pattern2);
            if (res.getHit() > 1) {
                worker.error(cmd, res.getText());
                return false;
            }
        }

        return true;
    }

    private void exitConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(exit_config_pattern);
            if (res.getHit() == 2)
                return;
        }
    }

    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        // apply one line at a time
        String lines[];
        int i;
        boolean isAtTop=true;
        long lastTime = System.currentTimeMillis();
        long time;

        if (!enterConfig(worker, cmd))
            // we encountered an error
            return;

        try {
            lines = data.split("\n");
            for (i=0 ; i < lines.length ; i++) {
                time = System.currentTimeMillis();
                if ((time - lastTime) > (0.8 * readTimeout)) {
                    lastTime = time;
                    worker.setTimeout(readTimeout);
                }
                isAtTop = print_line_wait(worker, cmd, lines[i].trim(), 0);
            }
        }
        catch (ApplyException e) {
            if (!e.isAtTop)
                moveToTopConfig();

            if (e.inConfigMode)
                exitConfig();

            throw e;
        }

        // make sure we have exited from all submodes
        if (!isAtTop)
            moveToTopConfig();

        exitConfig();
    }

    public void getTransId(NedWorker worker)
        throws Exception {
        session.setTracer(worker);

        // calculate checksum of config
        int i;

        session.print("show running-config | exclude able-management\n");
        session.expect("show running-config | exclude able-management",
                       worker);

        String res = session.expect(prompt_pattern, worker);

        i = res.indexOf("Current configuration :");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            res = res.substring(n+1);
        }

        i = res.indexOf("No entries found.");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            res = res.substring(n+1);
        }

        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // look for the string cpu-process and remove it
        i = res.indexOf(" cpu-process");
        while (i >= 0) {
            res = res.substring(0,i)+res.substring(i+12);
            i = res.indexOf(" cpu-process");
        }

        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        String md5String = md5Number.toString(16);

        worker.getTransIdResponse(md5String);

        return;
    }

    public void show(NedWorker worker, String toptag)
        throws Exception {
        session.setTracer(worker);
        int i;

        if (toptag.equals("interface")) {
            session.print(
                          "show running-config | exclude able-management\n");
            session.expect("show running-config | exclude able-management",
                           worker);

            String res = session.expect(prompt_pattern, worker);

            i = res.indexOf("Current configuration :");
            if (i >= 0) {
                int n = res.indexOf("\n", i);
                res = res.substring(n+1);
            }

            i = res.indexOf("No entries found.");
            if (i >= 0) {
                int n = res.indexOf("\n", i);
                res = res.substring(n+1);
            }

            i = res.lastIndexOf("\nend");
            if (i >= 0) {
                res = res.substring(0,i);
            }

            // look for the string cpu-process and remove it
            i = res.indexOf(" cpu-process");
            while (i >= 0) {
                res = res.substring(0,i)+res.substring(i+12);
                i = res.indexOf(" cpu-process");
            }

            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since the A10
            // cannot show different parts of the config.
            worker.showCliResponse("");
        }
    }

    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {

        worker.showStatsResponse(new NedTTL[] {
                new NedTTL(path, 10)
            });
    }

    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {

        worker.showStatsListResponse(10, null);
    }

    public boolean isConnection(String device_id,
                                InetAddress ip,
                                int port,
                                String proto,  // ssh or telnet
                                String ruser,
                                String pass,
                                String secpass,
                                String keydir,
                                boolean trace,
                                int connectTimeout, // milliSecs
                                int readTimeout,
                                int writeTimeout) {
        return ((this.device_id.equals(device_id)) &&
                (this.ip.equals(ip)) &&
                (this.port == port) &&
                (this.proto.equals(proto)) &&
                (this.ruser.equals(ruser)) &&
                (this.pass.equals(pass)) &&
                (this.secpass.equals(secpass)) &&
                (this.trace == trace) &&
                (this.connectTimeout == connectTimeout) &&
                (this.readTimeout == readTimeout) &&
                (this.writeTimeout == writeTimeout));
    }

    public NedCliBase newConnection(String device_id,
                                InetAddress ip,
                                int port,
                                String proto,  // ssh or telnet
                                String ruser,
                                String pass,
                                String secpass,
                                String publicKeyDir,
                                boolean trace,
                                int connectTimeout, // msec
                                int readTimeout,    // msec
                                int writeTimeout,   // msecs
                                NedMux mux,
                                NedWorker worker) {
        return new A10NedCli(device_id, ip, port, proto, ruser, pass,
                             secpass, trace,
                             connectTimeout, readTimeout, writeTimeout,
                             mux, worker);
    }
}

