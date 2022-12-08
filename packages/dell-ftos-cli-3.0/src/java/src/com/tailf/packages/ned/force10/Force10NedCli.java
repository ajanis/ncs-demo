package com.tailf.packages.ned.force10;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.Hashtable;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.InteractiveCallback;

import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfTag;
import com.tailf.conf.ConfXMLParam;
import com.tailf.maapi.Maapi;
import com.tailf.ncs.ResourceManager;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ned.CliSession;
import com.tailf.ned.NedCapability;
import com.tailf.ned.NedCliBase;
import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedTracer;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSession;
import com.tailf.ned.SSHSessionException;
import com.tailf.ned.TelnetSession;
import com.tailf.packages.ned.force10.namespaces.Force10Stats;

/**
 * This class implements NED interface for cisco ios routers
 *
 */

public class Force10NedCli extends NedCliBase  {
    private String device_id;

    private Connection connection;
    private CliSession session;

    private InetAddress ip;
    private int port;
    private String proto;  // ssh or telnet
    private String ruser;
    private String pass;
    private String secpass;
    private boolean trace;
    private NedTracer tracer;
    private int connectTimeout; // msec
    private int readTimeout;    // msec
    private int writeTimeout;    // msec
    private NedMux mux;
    private static Logger LOGGER  = Logger.getLogger(Force10NedCli.class);

    private static Hashtable<String,String> stats =
        new Hashtable<String,String>();

    private static String prompt = "\\A\\S+#";
    private static Pattern prompt_pattern;
    private static Pattern[]
        move_to_config_pattern,
        print_line_wait_pattern,
        print_line_wait_confirm_pattern,
        enter_config_pattern,
        enter_config_pattern2,
        exit_config_pattern;

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi           mm;

    static {
        prompt_pattern = Pattern.compile(prompt);

        move_to_config_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(conf\\)#"),
            Pattern.compile(".*\\(conf.*\\)#")
        };

        print_line_wait_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(conf\\)#"),
            Pattern.compile(".*\\(conf.*\\)#"),
            prompt_pattern
        };

        print_line_wait_confirm_pattern = new Pattern[] {
            Pattern.compile("Are you sure"),
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(conf\\)#"),
            Pattern.compile(".*\\(conf.*\\)#"),
            prompt_pattern
        };

        enter_config_pattern = new Pattern[] {
            Pattern.compile("Do you want to kill that session and continue"),
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(conf\\)#"),
            Pattern.compile(".*\\(conf.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

        enter_config_pattern2 = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(conf\\)#"),
            Pattern.compile(".*\\(conf.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

        exit_config_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(conf\\)#"),
            Pattern.compile(".*\\(conf.*\\)#"),
            prompt_pattern
        };
    }

    private class keyboardInteractive implements InteractiveCallback {
        private String pass;
        public keyboardInteractive(String password) {
            this.pass = password;
        }
        public String[] replyToChallenge(String name, String instruction,
                                         int numPrompts, String[] prompt,
                                         boolean[] echo) throws Exception {
            if (numPrompts == 0)
                return new String[] {};
            if (numPrompts != 1) {
                throw new Exception("giving up");
            }
            return new String[] { pass };
        }
    }

    public Force10NedCli() {
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    public Force10NedCli(String device_id,
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

        NedExpectResult res;

        this.device_id = device_id;
        this.ip = ip;
        this.port = port;
        this.proto = proto;
        this.ruser = ruser;
        this.pass = pass;
        this.secpass = secpass;
        this.trace = trace;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
        this.mux = mux;

        if (trace)
            tracer = worker;
        else
            tracer = null;

        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }

        worker.setTimeout(60*1000);

        try {
            try {
                if (proto.equals("ssh")) {
                    // ssh

                    trace(worker, "SSH connecting to host: "+
                          ip.getHostAddress()+":"+port, "out");

                    connection = new Connection(ip.getHostAddress(), port);
                    connection.connect(null, connectTimeout, 0);

                    String authMethods[] = connection.
                        getRemainingAuthMethods(ruser);
                    boolean hasPassword=false;
                    boolean hasKeyboardInteractive=false;

                    for(int i=0 ; i < authMethods.length ; i++) {
                        if (authMethods[i].equals("password"))
                            hasPassword = true;
                        else if (authMethods[i].equals("keyboard-interactive"))
                            hasKeyboardInteractive = true;
                    }

                    boolean isAuthenticated = false;
                    if (hasPassword) {
                        isAuthenticated = connection.authenticateWithPassword(
                                                       ruser, pass);
                    } else if (hasKeyboardInteractive) {
                        InteractiveCallback cb = new keyboardInteractive(pass);
                        isAuthenticated =
                            connection.authenticateWithKeyboardInteractive(
                                         ruser, cb);
                    }

                    if (!isAuthenticated) {
                        trace(worker, "SSH autentication failed", "out");
                        LOGGER.info("auth connect failed ");
                        worker.connectError(NedWorker.CONNECT_BADPASS,
                                            "Auth failed");
                        return;
                    }

                    trace(worker, "SSH initializing session", "out");

                    session = new SSHSession(connection, readTimeout,
                                             tracer, this);
                }
                else {
                    // Telnet
                    trace(worker, "TELNET connecting to host: "+
                          ip.getHostAddress()+":"+port, "out");

                    TelnetSession tsession =
                        new TelnetSession(ip.getHostAddress(), port, ruser,
                                          readTimeout, tracer, this);
                    trace(worker, "TELNET looking for login prompt", "out");
                    session = tsession;
                    try {
                        res = session.expect(new String[]
                            {"[Ll]ogin:", "[Nn]ame:", "[Pp]assword:"},
                                             worker);
                    } catch (Exception e) {
                        throw new NedException("No login prompt");
                    }
                    if (res.getHit() == 0 || res.getHit() == 1) {
                        session.println(ruser);
                        trace(worker, "TELNET looking for password prompt",
                              "out");
                        try {
                            session.expect("[Pp]assword:", worker);
                        } catch (Exception e) {
                            throw new NedException("No password prompt");
                        }
                    }
                    session.println(pass);
                    tsession.setScreenSize(200,24);
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
            session.print("terminal length 0\n");
            session.expect(prompt_pattern, worker);
            trace(worker, "Requesting version string", "out");
            session.print("show version\n");
            String version = session.expect(prompt_pattern, worker);

            /* look for version string */

            try {
                trace(worker, "Inspecting version string", "out");
                if (version.indexOf("Force10 Real Time") >= 0) {
                    // found FORCE10
                    NedCapability capas[] = new NedCapability[1];
                    NedCapability statscapas[] = new NedCapability[1];

                    capas[0] = new NedCapability(
                                    "",
                                    "http://tail-f.com/ned/dell-ftos",
                                    "tailf-ned-dell-ftos",
                                    "",
                                    "2013-04-09",
                                    "");

                    statscapas[0] = new NedCapability(
                                    "",
                                    "http://tail-f.com/ned/dell-ftos-stats",
                                    "tailf-ned-dell-ftos-stats",
                                    "",
                                    "2013-05-07",
                                    "");


                    setConnectionData(capas,
                                      statscapas,
                                      true,  // want reverse-diff
                                      TransactionIdMode.UNIQUE_STRING // NONE
                                      );
                } else {
                    worker.error(NedCmd.CONNECT_CLI, "unknown device");
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

    public void trace(NedWorker worker, String msg, String direction) {
        if (trace) {
            worker.trace("-- "+msg+" --\n", direction, device_id);
        }
    }

    public void reconnect(NedWorker worker) {
        // all capas and transmode already set in constructor
        // nothing needs to be done
    }

    public String device_id() {
        return device_id;
    }

    // should return "cli" or "generic"
    public String type() {
        return "cli";
    }

    // Which Yang modules are covered by the class
    public String [] modules() {
        return new String[] { "tailf-ned-dell-ftos" };
    }

    // Which identity is implemented by the class
    public String identity() {
        return "force10-id:dell-ftos";
    }

    private void moveToTopConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(move_to_config_pattern);
            if (res.getHit() == 0 || res.getHit() == 1)
                return;
        }
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(print_line_wait_pattern, worker);

        if (res.getHit() == 0 || res.getHit() == 1)
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
                        worker.setTimeout(10*60*1000);
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
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(print_line_wait_confirm_pattern, worker);

        if (res.getHit() == 0)
            return print_line_wait(worker, cmd, "y", 0);
        else if (res.getHit() == 1 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 3)
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
                        worker.setTimeout(10*60*1000);

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
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(new Pattern[] {prompt_pattern}, worker);

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

        session.print("config terminal\n");
        res = session.expect(enter_config_pattern, worker);
        if (res.getHit() > 3) {
            worker.error(cmd, res.getText());
            return false;
        } else if (res.getHit() == 0) {
            session.print("yes\n");
            res = session.expect(enter_config_pattern2, worker);
            if (res.getHit() > 2) {
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
            if (res.getHit() == 3)
                return;
        }
    }

    private class ApplyException extends Exception {
        public boolean isAtTop;
        public boolean inConfigMode;

        public ApplyException(String msg, boolean isAtTop,
                              boolean inConfigMode) {
            super(msg);
            this.isAtTop = isAtTop;
            this.inConfigMode = inConfigMode;
        }
        public ApplyException(String line, String msg,
                              boolean isAtTop, boolean inConfigMode) {
            super("(command: "+line+"): "+msg);
            this.isAtTop = isAtTop;
            this.inConfigMode = inConfigMode;
        }
    }

    private void applyConfig(NedWorker worker, int cmd, String data)
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
                lines[i] = lines[i].replaceAll("used-by all", "");
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

    // mangle output, or just pass through we're invoked during prepare phase
    // of NCS
    public void prepare(NedWorker worker, String data)
        throws Exception {

        if (trace)
            session.setTracer(worker);

        applyConfig(worker, NedCmd.PREPARE_CLI, data);
        worker.prepareResponse();
    }

    public void prepareDry(NedWorker worker, String data)
        throws Exception {
        worker.prepareDryResponse(data);
    }

    // mangle output, we're invoked during prepare phase
    // of NCS
    public void abort(NedWorker worker, String data)
        throws Exception {

        if (trace)
            session.setTracer(worker);

        applyConfig(worker, NedCmd.ABORT_CLI, data);
        worker.abortResponse();
    }

    // mangle output, we're invoked during prepare phase
    // of NCS
    public void revert(NedWorker worker, String data)
        throws Exception {

        if (trace)
            session.setTracer(worker);

        applyConfig(worker, NedCmd.REVERT_CLI, data);
        worker.revertResponse();
    }

    public void commit(NedWorker worker, int timeout)
        throws Exception {
        if (trace)
            session.setTracer(worker);
        print_line_wait_oper(worker, NedCmd.COMMIT, "write memory");
        worker.commitResponse();
    }

    public void persist(NedWorker worker) throws Exception {
        if (trace)
            session.setTracer(worker);
        worker.persistResponse();
    }

    public void close(NedWorker worker)
        throws Exception {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        if (session != null) {
            if (trace)
                session.setTracer(worker);
            session.print("exit\n");
            session.close();
        }
        if (connection != null)
            connection.close();
    }

    public void close() {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        try {
            if (session != null) {
                if (trace)
                    session.setTracer(null);
                session.print("exit\n");
                session.close();
            }
            if (connection != null)
                connection.close();
        } catch (Exception e) {}
    }

    public boolean isAlive() {
        return session.serverSideClosed() == false;
    }

    public void getTransId(NedWorker worker)
        throws Exception {
        if (trace)
            session.setTracer(worker);

        String res = get_config(worker);

        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        String md5String = md5Number.toString(16);

        worker.getTransIdResponse(md5String);

        return;
    }

    private String get_config(NedWorker worker)
        throws Exception {
        int i;

        session.print("show running-config\n");
        session.expect("show running-config", worker);

        String res = session.expect(prompt_pattern, worker);

        i = res.indexOf("Current Configuration");
        if (i >= 0) {
            int n = res.indexOf("\n", i);
            res = res.substring(n+1);
        }

        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        return res;
    }


    public void show(NedWorker worker, String toptag)
        throws Exception {
        if (trace)
            session.setTracer(worker);
        int i;

        if (toptag.equals("interface")) {

            String res = get_config(worker);

            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since the FORCE10
            // cannot show different parts of the config.
            worker.showCliResponse("");
        }
    }

    private void populateStatsMac(NedWorker worker, int th)
        throws Exception {
        mm.attach(th, -1, 1);
        Maapi m = mm;

        long readtime = System.currentTimeMillis();

        String root =
            "/ncs:devices/device{" + device_id + "}" +
            "/live-status/stats";


        try {
            mm.delete(th, root+"/mac-address-table");
        } catch (Exception e) {
            System.err.println("error "+e);
        }

        /**********************************************************************
         * Read mac-address-table
         */

        session.print("show mac-address-table\n");
        session.expect("show mac-address-table", worker);

        String res = session.expect(prompt_pattern, worker);


        // We should now have a table of stats

        String lines[] = res.split("\n");

        // skip header
        int i;

        for(i=0 ; i < lines.length ; i++) {
            if (lines[i].indexOf("VlanId") >= 0) {
                i++;
                break;
            }
        }

        for( ; i < lines.length ; i++) {
            String cols[] = lines[i].trim().split("( |\t)+");

            if (cols.length < 5)
                // done
                break;

            String ipath = root+
                "/mac-address-table{"+cols[0]+" "+cols[1]+"}";
            mm.create(th, ipath);

            mm.setElem(th, cols[2], ipath+"/type");
            mm.setElem(th, cols[3]+cols[4], ipath+"/interface");
            mm.setElem(th, cols[5], ipath+"/state");
        }

        mm.detach(th);
    }

    private void populateStatsInt(NedWorker worker, int th)
        throws Exception {
        mm.attach(th, -1, 1);
        Maapi m = mm;

        long readtime = System.currentTimeMillis();

        String root =
            "/ncs:devices/device{" + device_id + "}" +
            "/live-status/stats";


        try {
            mm.delete(th, root+"/interface");
        } catch (Exception e) {
            System.err.println("error "+e);
        }

        /**********************************************************************
  Read interface stats on the format

  TenGigabitEthernet 0/0 is up, line protocol is down
  Hardware is DellForce10Eth, address is 00:01:e8:8b:7c:69
      Current address is 00:01:e8:8b:7c:69
  Pluggable media not present
  Interface index is 33620994
  Internet address is not set
  MTU 1554 bytes, IP MTU 1500 bytes
  LineSpeed auto
  Flowcontrol rx on tx off
  ARP type: ARPA, ARP Timeout 04:00:00
  Last clearing of "show interface" counters 2d13h6m
  Queueing strategy: fifo
  Input Statistics:
       0 packets, 0 bytes
       0 64-byte pkts, 0 over 64-byte pkts, 0 over 127-byte pkts
       0 over 255-byte pkts, 0 over 511-byte pkts, 0 over 1023-byte pkts
       0 Multicasts, 0 Broadcasts
       0 runts, 0 giants, 0 throttles
       0 CRC, 0 overrun, 0 discarded
  Output Statistics:
       0 packets, 0 bytes, 0 underruns
       0 64-byte pkts, 0 over 64-byte pkts, 0 over 127-byte pkts
       0 over 255-byte pkts, 0 over 511-byte pkts, 0 over 1023-byte pkts
       0 Multicasts, 0 Broadcasts, 0 Unicasts
       0 throttles, 0 discarded, 0 collisions, 0 wreddrops
  Rate info (interval 299 seconds):
       Input 00.00 Mbits/sec,          0 packets/sec, 0.00% of line-rate
       Output 00.00 Mbits/sec,          0 packets/sec, 0.00% of line-rate
  Time since last interface status change: 1w3d14h
         */

        session.print("show interfaces\n");
        session.expect("show interfaces", worker);

        String res = session.expect(prompt_pattern, worker);

        String interfaces[] = res.split("(\r|\n)(\r|\n)(\r|\n)+");

        String lines[];

        for(int i=0 ; i < interfaces.length ; i++) {
            int x = 0;
            lines = interfaces[i].trim().split("(\n|\r)+");

            /* TenGigabitEthernet 0/4 is up, line protocol is down */
            String word[] = lines[x++].split(",* +");

            String iroot = root+
                "/interface{"+word[0]+" "+word[1]+"}";

            mm.create(th, iroot);

            mm.setElem(th, word[3], iroot+"/state");
            if (word[7].equals("not")) {
                mm.setElem(th, word[7]+" "+word[8], iroot+"/line-protocol");
            }
            else {
                mm.setElem(th, word[7], iroot+"/line-protocol");
            }

            for( ; x < lines.length ; x++) {
                word = lines[x].split(",* +");
                /* Hardware is DellForce10Eth, address is 00:01:e8:8b:7c:69 */
                if (word[0].equals("Hardware")) {
                    if (!word[5].equals("not")) {
                        mm.setElem(th, word[5], iroot+"/hw-address");
                    }
                }
                /* Current address is 00:01:e8:8b:7c:69 */
                else if (word[0].equals("Current")) {
                    mm.setElem(th, word[3], iroot+"/current-address");
                }
                /* Interface index is 34669570 */
                else if (word[0].equals("Interface")) {
                    mm.setElem(th, word[3], iroot+"/index");
                }
                /* Internet address is not set */
                else if (word[0].equals("Internet")) {
                    if (!word[3].equals("not")) {
                        mm.setElem(th, word[3], iroot+"/internet-address");
                    }
                }
                /* MTU 1554 bytes, IP MTU 1500 bytes */
                else if (word[0].equals("MTU")) {
                    mm.setElem(th, word[1], iroot+"/mtu");
                    mm.setElem(th, word[5], iroot+"/ip-mtu");
                }
                /* LineSpeed auto */
                /* LineSpeed 1000 Mbit, Mode full duplex */
                else if (word[0].equals("LineSpeed")) {
                    if (word[1].equals("auto")) {
                        mm.setElem(th, word[1], iroot+"/line-speed");
                    }
                    else {
                        mm.setElem(th, word[1]+" "+word[2],
                                   iroot+"/line-speed");
                        if (word.length >= 5)
                            mm.setElem(th, word[5], iroot+"/line-mode");
                    }
                }
                /* Flowcontrol rx on tx off */
                else if (word[0].equals("Flowcontrol")) {
                    mm.setElem(th, word[2], iroot+"/flowcontrol-rx");
                    mm.setElem(th, word[4], iroot+"/flowcontrol-tx");
                }
                /* ARP type: ARPA, ARP Timeout 04:00:00 */
                else if (word[0].equals("ARP")) {
                    mm.setElem(th, word[2], iroot+"/arp-type");
                    mm.setElem(th, word[5], iroot+"/arp-timeout");
                }
                /* Last clearing of "show interface" counters 2d13h8m */
                else if (word[0].equals("Last")) {
                    mm.setElem(th, word[6], iroot+"/last-clearing-counter");
                }
                /* Queueing strategy: fifo */
                else if (word[0].equals("Queueing")) {
                    mm.setElem(th, word[2], iroot+"/queue-strategy");
                }
                /*
 Input Statistics:
         0 packets, 0 bytes
         0 64-byte pkts, 0 over 64-byte pkts, 0 over 127-byte pkts
         0 over 255-byte pkts, 0 over 511-byte pkts, 0 over 1023-byte pkts
         0 Multicasts, 0 Broadcasts
         0 runts, 0 giants, 0 throttles
         0 CRC, 0 overrun, 0 discarded
                */
                else if (word[0].equals("Input") &&
                         word[1].equals("Statistics:")) {
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/input/packets");
                    mm.setElem(th, word[2], iroot+"/input/bytes");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/input/pkts-64-byte");
                    mm.setElem(th, word[3], iroot+"/input/pkts-over-64-byte");
                    mm.setElem(th, word[7], iroot+"/input/pkts-over-127-byte");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/input/pkts-over-255-byte");
                    mm.setElem(th, word[4], iroot+"/input/pkts-over-511-byte");
                    mm.setElem(th, word[8], iroot+"/input/pkts-over-1023-byte");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/input/multicasts");
                    mm.setElem(th, word[2], iroot+"/input/broadcasts");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/input/runts");
                    mm.setElem(th, word[2], iroot+"/input/giants");
                    mm.setElem(th, word[4], iroot+"/input/throttles");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/input/crc");
                    mm.setElem(th, word[2], iroot+"/input/overrun");
                    mm.setElem(th, word[4], iroot+"/input/discarded");
                }
                /*
  Output Statistics:
          0 packets, 0 bytes, 0 underruns
          0 64-byte pkts, 0 over 64-byte pkts, 0 over 127-byte pkts
          0 over 255-byte pkts, 0 over 511-byte pkts, 0 over 1023-byte pkts
          0 Multicasts, 0 Broadcasts, 0 Unicasts
          0 throttles, 0 discarded, 0 collisions, 0 wreddrops
                */
                else if (word[0].equals("Output") &&
                         word[1].equals("Statistics:")) {
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/output/packets");
                    mm.setElem(th, word[2], iroot+"/output/bytes");
                    mm.setElem(th, word[4], iroot+"/output/underruns");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/output/pkts-64-byte");
                    mm.setElem(th, word[3], iroot+"/output/pkts-over-64-byte");
                    mm.setElem(th, word[7], iroot+"/output/pkts-over-127-byte");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/output/pkts-over-255-byte");
                    mm.setElem(th, word[4], iroot+"/output/pkts-over-511-byte");
                    mm.setElem(th, word[8], iroot+
                               "/output/pkts-over-1023-byte");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/output/multicasts");
                    mm.setElem(th, word[2], iroot+"/output/broadcasts");
                    mm.setElem(th, word[4], iroot+"/output/unicasts");
                    word = lines[++x].trim().split(",* +");
                    mm.setElem(th, word[0], iroot+"/output/throttles");
                    mm.setElem(th, word[2], iroot+"/output/discarded");
                    mm.setElem(th, word[4], iroot+"/output/collisions");
                    mm.setElem(th, word[4], iroot+"/output/wreddrops");
                }
                /*
Rate info (interval 299 seconds):
      Input 00.00 Mbits/sec,          0 packets/sec, 0.00% of line-rate
      Output 00.00 Mbits/sec,          0 packets/sec, 0.00% of line-rate
                */
                else if (word[0].equals("Rate") &&
                         word[1].equals("info")) {
                    word = lines[x+1].trim().split(",* +");
                    if (word[0].equals("Input")) {
                        String xpath = iroot+"/rate-info/input/";
                        mm.setElem(th, word[1], xpath+"mbit-p-sec");
                        mm.setElem(th, word[3], xpath+"packets-p-sec");
                        mm.setElem(th, word[5], xpath+"percent-line-rate");
                        x++;
                    }
                    word = lines[x+1].trim().split(",* +");
                    if (word[0].equals("Output")) {
                        String xpath = iroot+"/rate-info/output/";
                        mm.setElem(th, word[1], xpath+"mbit-p-sec");
                        mm.setElem(th, word[3], xpath+"packets-p-sec");
                        mm.setElem(th, word[5], xpath+"percent-line-rate");
                        x++;
                    }
                }
                /* Time since last interface status change: 1w3d14h */
                else if (word[0].equals("Time")) {
                    mm.setElem(th, word[6],
                               iroot+"/time-since-last-status-change");
                }
                else {
                    /* found unknown stats line */
                    // System.err.println("skipping: "+lines[x]);
                }
            }
        }

        mm.detach(th);
    }

    private void populateStats(NedWorker worker, int th)
        throws Exception {
        populateStatsMac(worker, th);
        populateStatsInt(worker, th);
    }

    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {

        // populateStats(worker, th);

        worker.showStatsResponse(new NedTTL[] {
                new NedTTL(path, 20)
            });
    }

    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {

        ConfObject[] kp = path.getKP();
        ConfTag t = (ConfTag) kp[0];

        if (t.getTagHash() == Force10Stats._mac_address_table) {
            populateStatsMac(worker, th);
        }
        else if (t.getTagHash() == Force10Stats._interface) {
            populateStatsInt(worker, th);
        }

        worker.showStatsListResponse(20, null);
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


    public void command(NedWorker worker, String cmdname, ConfXMLParam[] p)
        throws NedException, IOException {
        if (trace)
            session.setTracer(worker);

        worker.error(NedCmd.CMD, "not implemented");
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
        return new Force10NedCli(device_id, ip, port, proto, ruser, pass,
                             secpass, trace,
                             connectTimeout, readTimeout, writeTimeout,
                             mux, worker);
    }

    public String toString() {
        return device_id+"-"+ip.toString()+":"+
            Integer.toString(port)+"-"+proto;
    }
}
