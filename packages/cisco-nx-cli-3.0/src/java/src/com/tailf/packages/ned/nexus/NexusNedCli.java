package com.tailf.packages.ned.nexus;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.InteractiveCallback;

import com.tailf.conf.ConfPath;
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
import com.tailf.ned.NedTracer;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSession;
import com.tailf.ned.SSHSessionException;
import com.tailf.ned.TelnetSession;

/**
 * This class implements NED interface for cisco ios corouters
 *
 */

public class NexusNedCli extends NedCliBase  {
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
    private static Logger LOGGER  = Logger.getLogger(NexusNedCli.class);

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi           mm;


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

    public NexusNedCli() {
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    public NexusNedCli(String device_id,
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

        try {
            try {
                if (proto.equals("ssh")) {
                    // ssh

                    connection = new Connection(ip.getHostAddress(), port);
                    connection.connect(null, connectTimeout, 0);

                    String authMethods[] =
                        connection.getRemainingAuthMethods(ruser);
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
                        isAuthenticated =
                            connection.authenticateWithPassword(ruser, pass);
                    } else if (hasKeyboardInteractive) {
                        InteractiveCallback cb = new keyboardInteractive(pass);
                        isAuthenticated =
                            connection.
                            authenticateWithKeyboardInteractive(ruser, cb);
                    }

                    if (!isAuthenticated) {
                        LOGGER.info("auth connect failed ");
                        worker.connectError(NedWorker.CONNECT_BADPASS,
                                            "Auth failed");
                        return;
                    }
                    session = new SSHSession(connection, readTimeout,
                                             tracer, this);
                }
                else {
                    // Telnet
                    TelnetSession tsession =
                        new TelnetSession(ip.getHostAddress(), port, ruser,
                                          readTimeout, tracer, this);
                    session = tsession;

                    NedExpectResult res;
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
                    // tsession.setScreenSize(200,65000);
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
            NedExpectResult res;

            if (trace)
                session.setTracer(worker);

            res = session.expect(new String[] {"\\A\\S*>", "\\A\\S*#"},
                                 worker);
            if (res.getHit() == 0) {
                session.print("enable\n");
                res = session.expect(new String[] {"Password:", "\\A\\S*#"},
                                     worker);
                if (res.getHit() == 0) {
                    session.print(secpass+"\n"); // enter password here
                    session.expect("\\A\\S*#", worker);
                }
            }
            session.print("terminal length 0\n");
            session.expect("\\A\\S*#", worker);
            session.print("terminal width 0\n");
            session.expect("\\A\\S*#", worker);
            session.print("show version\n");
            String version = session.expect("\\A\\S*#", worker);

            /* look for version string */

            if (version.indexOf("Cisco Nexus Operating System") >= 0) {
                // found Nexus
                NedCapability capas[] = new NedCapability[1];
                NedCapability statscapas[] = new NedCapability[0];

                capas[0] = new NedCapability(
                    "",
                    "http://tail-f.com/ned/cisco-nx",
                    "tailf-ned-cisco-nx",
                    "",
                    "2011-03-24",
                    "");

                setConnectionData(capas,
                                  statscapas,
                                  true,
                                  TransactionIdMode.UNIQUE_STRING);
            } else {
                worker.error(NedCmd.CONNECT_CLI, "unknown device");
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
        return new String[] { "nexus" };
    }

    // Which identity is implemented by the class
    public String identity() {
        return "cisco-nx-id:cisco-nx";
    }

    private void moveToTopConfig() throws IOException,SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(new String[]
                {"\\A\\S*\\(config\\)#", "\\A\\S*\\(config.*\\)#"});
            if (res.getHit() == 0)
                return;
        }
    }

    private String stringQuote(String aText) {
        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char character =  iterator.current();
        result.append("\"");
        while (character != CharacterIterator.DONE ){
            if (character == '"')
                result.append("\\\"");
            else if (character == '\\')
                result.append("\\\\");
            else if (character == '\b')
                result.append("\\b");
            else if (character == '\n')
                result.append("\\n");
            else if (character == '\r')
                result.append("\\r");
            else if (character == (char) 11) // \v
                result.append("\\v");
            else if (character == '\f')
                result.append("'\f");
            else if (character == '\t')
                result.append("\\t");
            else if (character == (char) 27) // \e
                result.append("\\e");
            else
                //the char is not a special one
                //add it to the result as is
                result.append(character);
            character = iterator.next();
        }
        result.append("\"");
        return result.toString();
    }

    private String stringDequote(String aText) {
        if (aText.indexOf("\"") != 0)
            return aText;

        aText = aText.substring(1,aText.length()-1);

        StringBuilder result = new StringBuilder();
        StringCharacterIterator iterator =
            new StringCharacterIterator(aText);
        char c1 = iterator.current();

        while (c1 != CharacterIterator.DONE ) {
            if (c1 == '\\') {
                char c2 = iterator.next();
                if (c2 == CharacterIterator.DONE )
                    result.append(c1);
                else if (c2 == 'b')
                    result.append('\b');
                else if (c2 == 'n')
                    result.append('\n');
                else if (c2 == 'r')
                    result.append('\r');
                else if (c2 == 'v')
                    result.append((char) 11); // \v
                else if (c2 == 'f')
                    result.append('\f');
                else if (c2 == 't')
                    result.append('\t');
                else if (c2 == 'e')
                    result.append((char) 27); // \e
                else {
                    result.append(c2);
                    c1 = iterator.next();
                }
            }
            else {
                //the char is not a special one
                //add it to the result as is
                result.append(c1);
                c1 = iterator.next();
            }
        }
        return result.toString();
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(new String[] {
                "\\A\\S*\\(config\\)#", "\\A\\S*\\(cfg\\)#",
                "\\A\\S*\\(config.*\\)#", "\\A\\S*\\(cfg.*\\)#","\\A\\S*#"
            }, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 1 || res.getHit() == 3)
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

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying, boolean waitForEcho)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        if (waitForEcho)
            session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(new String[] {
                "\\A\\S*\\(config\\)#", "\\A\\S*\\(cfg\\)#",
                "\\A\\S*\\(config.*\\)#", "\\A\\S*\\(cfg.*\\)#","\\A\\S*#"
            }, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 1 || res.getHit() == 3)
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
                    return print_line_wait(worker, cmd, line, retrying+1,
                                           waitForEcho);
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
        res = session.expect(new String[]
            {"Are you sure",
             "\\A\\S*\\(config\\)#",
             "\\A\\S*\\(cfg\\)#",
             "\\A\\S*\\(config.*\\)#",
             "\\A\\S*\\(cfg.*\\)#",
             "\\A\\S*#"}, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            return print_line_wait(worker, cmd, "y", 0);
        else if (res.getHit() == 1 || res.getHit() == 3)
            isAtTop = true;
        else if (res.getHit() == 4)
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
                    return print_line_wait_confirm(worker, cmd, line,
                                                   retrying+1);
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
        res = session.expect(new String[] {"\\A\\S*#"}, worker);

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
        res = session.expect(new String[]
            {"Do you want to kill that session and continue",
             "\\A\\S*\\(config\\)#",
             "\\A\\S*\\(config.*\\)#",
             "Aborted.*\n",
             "Error.*\n",
             "syntax error.*\n",
             "error:.*\n"}, worker);
        if (res.getHit() > 2) {
            worker.error(cmd, res.getText());
            return false;
        } else if (res.getHit() == 0) {
            session.print("yes\n");
            res = session.expect(new String[]
                {"\\A\\S*\\(config\\)#",
                 "\\A\\S*\\(cfg\\)#",
                 "\\A\\S*\\(config.*\\)#",
                 "\\A\\S*\\(cfg.*\\)#",
                 "Aborted.*\n",
                 "Error.*\n",
                 "syntax error.*\n",
                 "error:.*\n"}, worker);
            if (res.getHit() > 3) {
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
            res = session.expect(new String[]
                {"\\A\\S*\\(config\\)#",
                 "\\A\\S*\\(cfg\\)#",
                 "\\A\\S*\\(config.*\\)#",
                 "\\A\\S*\\(cfg.*\\)#",
                 "\\A\\S*#"});
            if (res.getHit() == 4)
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
                lines[i] = lines[i].trim();
                String line = lines[i];
                boolean waitForEcho = true;
                if (line.indexOf("banner ") == 0) {
                    int n = line.indexOf(" ", 9);
                    int banner_start = n+1;
                    String delim = line.substring(n+1, n+2);
                    if (delim.equals("^")) {
                        delim = line.substring(n+1, n+3);
                        banner_start = n+2;
                    }
                    int end_i = line.indexOf(delim, banner_start+1);
                    String banner = stringDequote(line.substring(banner_start,
                                                                 end_i));

                    line = line.substring(0, n)+delim+" "+banner+" "+delim;
                    waitForEcho = false;
                }
                isAtTop = print_line_wait(worker, cmd, line, 0, waitForEcho);
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
        throws NedException, IOException {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        if (session != null) {
            if (trace)
                session.setTracer(worker);
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

        // calculate checksum of config
        int i;

        session.print("show running-config\n");
        session.expect("show running-config", worker);

        String res = session.expect("\\A.*#", worker);

        i = res.indexOf("version");
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
        if (trace)
            session.setTracer(worker);
        int i;

        if (toptag.equals("interface")) {
            session.print("show running-config\n");
            session.expect("show running-config", worker);

            String res = session.expect("\\A\\S*#", worker);

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

            // strip out all snmp-server settings for now
            i = res.indexOf("\nsnmp-server");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\nsnmp-server");
            }

            // strip out all boot settings for now
            i = res.indexOf("\nboot");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\nboot");
            }

            // strip out all line settings for now
            i = res.indexOf("\nline");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\nline");
            }

            // strip out all aaa settings for now
            i = res.indexOf("\naaa");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                while(res.indexOf(" ",n+1) == n+1) {
                    n = res.indexOf("\n", n+1);
                }
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\naaa");
            }

            // look for banner and process separately
            i = res.indexOf ("\nbanner ");
            if (i >= 0) {
                int n=res.indexOf(" ", i+9);
                int start_banner = n+2;
                String delim = res.substring(n+1, n+2);
                if (delim.equals("^")) {
                    delim = res.substring(n+1,n+3);
                    start_banner = n+3;
                }
                int end_i = res.indexOf(delim, start_banner);
                String banner = stringQuote(res.substring(start_banner, end_i));
                res = res.substring(0,n+1)+delim+
                    " "+banner+" "+delim+res.substring(end_i+delim.length());

            }

            i = res.lastIndexOf("\nend");
            if (i >= 0) {
                res = res.substring(0,i);
            }

            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since the Nexus
            // cannot show different parts of the config.
            worker.showCliResponse("");
        }
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
                                int connectTimeout, // sec
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
        throws Exception {
        if (trace)
            session.setTracer(worker);

        worker.error(NedCmd.CMD, "not implemented");
    }

    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {
    }

    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {
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
                                int connectTimeout, // sec
                                int readTimeout,    // sec
                                int writeTimeout,   // msecs
                                NedMux mux,
                                NedWorker worker) {
        return new NexusNedCli(device_id, ip, port, proto, ruser,
                               pass, secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }

    public String toString() {
        return device_id+"-"+ip.toString()+":"+Integer.toString(port)+"-"+proto;
    }
}

