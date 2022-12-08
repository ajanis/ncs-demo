package com.tailf.packages.ned.iosxr;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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
import com.tailf.ned.NedErrorCode;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSession;
import com.tailf.ned.SSHSessionException;
import com.tailf.ned.TelnetSession;

/**
 * This class implements NED interface for cisco iosxr routers
 *
 */

public class IosxrNedCli extends NedCliBase  {
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
    private int connectTimeout; // msec
    private int readTimeout;    // msec
    private int writeTimeout;    // msec
    private NedMux mux;
    private static Logger LOGGER  = Logger.getLogger(IosxrNedCli.class);
    private static String prompt = "\\A\\S*#";
    private boolean inConfig = false;
    private boolean hasConfirming = false;

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi           mm;

    private final static Pattern[]
        move_to_top_pattern,
        noprint_line_wait_pattern,
        print_line_wait_pattern,
        print_line_wait_confirm_pattern,
        enter_config_pattern,
        exit_config_pattern;

    static {
        move_to_top_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(config.*\\)#")
        };

        noprint_line_wait_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt)
        };

        print_line_wait_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt)
        };

        print_line_wait_confirm_pattern = new Pattern[] {
            Pattern.compile("Are you sure"),
            Pattern.compile("Proceed"),
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt)
        };

        enter_config_pattern = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config.*\\)#"),
            Pattern.compile("\\A\\S*#")
        };

        exit_config_pattern = new Pattern[] {
            Pattern.compile(".*\\(config\\)#"),
            Pattern.compile(".*\\(cfg\\)#"),
            Pattern.compile(".*\\(config.*\\)#"),
            Pattern.compile(".*\\(cfg.*\\)#"),
            Pattern.compile(prompt),
            Pattern.compile("You are exiting after a 'commit confirm'")
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

    public IosxrNedCli() {
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    public IosxrNedCli(String device_id,
               InetAddress ip,
               int port,
               String proto,  // ssh or telnet
               String ruser,
               String pass,
               String secpass,
               boolean trace,
               int connectTimeout, // msec
               int readTimeout,    // msec
               int writeTimeout,   // msec
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
                        isAuthenticated = connection.
                            authenticateWithPassword(ruser, pass);
                    } else if (hasKeyboardInteractive) {
                        InteractiveCallback cb = new keyboardInteractive(pass);
                        isAuthenticated = connection.
                            authenticateWithKeyboardInteractive(ruser, cb);
                    }

                    if (!isAuthenticated) {
                        LOGGER.info("auth connect failed ");
                        worker.connectError(NedWorker.CONNECT_BADPASS,
                                            "Auth failed");
                        return;
                    }

                    session = new SSHSession(connection, readTimeout,
                                             worker, this);
                }
                else {
                    // Telnet
                    TelnetSession tsession =
                        new TelnetSession(ip.getHostAddress(), port, ruser,
                                          readTimeout, worker, this);
                    session = tsession;

                    NedExpectResult res;
                    try {
                        res = session.expect(new String[]
                            {"[Ll]ogin:", "[Nn]ame:", "[Pp]assword:"}, worker);
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
                    tsession.setScreenSize(200,65000);
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

            res = session.expect(new String[] {prompt}, worker);
            session.print("terminal length 0\n");
            session.expect(prompt, worker);
            session.print("terminal width 0\n");
            session.expect(prompt, worker);
            session.print("show version brief\n");
            String version = session.expect(prompt, worker);

            /* look for version string */

            if (version.indexOf("Cisco IOS XR Software") >= 0) {
                // found Iosxr
                NedCapability capas[] = new NedCapability[1];
                NedCapability statscapas[] = new NedCapability[1];

                capas[0] = new NedCapability(
                    "",
                    "http://tail-f.com/ned/cisco-ios-xr",
                    "tailf-ned-cisco-ios-xr",
                    "",
                    "2014-02-18",
                    "");

                statscapas[0] = new NedCapability(
                    "",
                    "http://tail-f.com/ned/cisco-ios-xr-stats",
                    "tailf-ned-cisco-ios-xr-stats",
                    "",
                    "2014-02-18",
                    "");

                setConnectionData(capas,
                                  statscapas,
                                  false, // want reverse-diff
                                  TransactionIdMode.NONE);
            } else {
                worker.error(NedCmd.CONNECT_CLI,
                             NedCmd.cmdToString(NedCmd.CONNECT_CLI),
                             "unknown device");
            }
        }
        catch (SSHSessionException e) {
            worker.error(NedCmd.CONNECT_CLI,
                         NedCmd.cmdToString(NedCmd.CONNECT_CLI),
                         e.getMessage());
        }
        catch (IOException e) {
            worker.error(NedCmd.CONNECT_CLI,
                         NedCmd.cmdToString(NedCmd.CONNECT_CLI),
                         e.getMessage());
        }
        catch (Exception e) {
            worker.error(NedCmd.CONNECT_CLI,
                         NedCmd.cmdToString(NedCmd.CONNECT_CLI),
                         e.getMessage());
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
        return new String[] { "tailf-ned-cisco-ios-xr" };
    }

    // Which identity is implemented by the class
    public String identity() {
        return "cisco-ios-xr-id:cisco-ios-xr";
    }

    private void moveToTopConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(move_to_top_pattern);
            if (res.getHit() == 0)
                return;
        }
    }

    private boolean isCliError(String reply) {
        if (reply.indexOf("hqm_tablemap_inform: CLASS_REMOVE error") >= 0)
            // 'error' when "no table-map <name>", but entry is removed
            return false;

        if (reply.toLowerCase().indexOf("error") >= 0 ||
            reply.toLowerCase().indexOf("aborted") >= 0 ||
            reply.toLowerCase().indexOf("exceeded") >= 0 ||
            reply.toLowerCase().indexOf("invalid") >= 0 ||
            reply.toLowerCase().indexOf("incomplete") >= 0 ||
            reply.toLowerCase().indexOf("duplicate name") >= 0 ||
            reply.toLowerCase().indexOf("may not be configured") >= 0 ||
            reply.toLowerCase().indexOf("should be in range") >= 0 ||
            reply.toLowerCase().indexOf("is used by") >= 0 ||
            reply.toLowerCase().indexOf("being used") >= 0 ||
            reply.toLowerCase().indexOf("cannot be deleted") >= 0 ||
            reply.toLowerCase().indexOf("bad mask") >= 0 ||
            reply.toLowerCase().indexOf("failed") >= 0) {
            return true;
        }
        return false;
    }

    private boolean noprint_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        line = line.trim();

        // first, expect the echo of the line we sent
        session.expect(new String[] { Pattern.quote(line) }, worker);
        // FIXME: explain prompt matching
        res = session.expect(noprint_line_wait_pattern, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 1 || res.getHit() == 3)
            isAtTop = false;
        else
            throw new ApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i = 0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                // return the line we sent, and the error line string
                throw new ApplyException(line, lines[i], isAtTop, true);
            }
        }

        return isAtTop;
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(print_line_wait_pattern, worker);

        if (res.getHit() == 0 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 1 || res.getHit() == 3)
            isAtTop = false;
        else
            throw new ApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                throw new ApplyException(line, lines[i], isAtTop, true);
            }
        }

        return isAtTop;
    }

    private boolean print_line_wait_confirm(NedWorker worker,
                                            int cmd, String line,
                                            int retrying)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;

        session.print(line+"\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(print_line_wait_confirm_pattern, worker);

        if (res.getHit() < 2)
            return print_line_wait(worker, cmd, "y", 0);
        else if (res.getHit() == 2 || res.getHit() == 4)
            isAtTop = true;
        else if (res.getHit() == 5)
            isAtTop = false;
        else
            throw new ApplyException(line, "exited from config mode",
                                     false, false);

        String lines[] = res.getText().split("\n|\r");
        for(int i=0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                throw new ApplyException(line, lines[i], isAtTop, true);
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
        res = session.expect(new String[] {prompt}, worker);

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

        session.print("config exclusive\n");
        res = session.expect(enter_config_pattern, worker);
        if (res.getHit() > 0) {
            worker.error(cmd, NedErrorCode.IN_USE, res.getText());
            return false;
        }

        inConfig = true;

        return true;
    }

    private void exitConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(exit_config_pattern);
            if (res.getHit() == 4) {
                inConfig = false;
                return;
            }
            else if (res.getHit() == 5) {
                session.print("yes\n");
                session.expect(prompt);
                inConfig = false;
                return;
            }
        }
    }

    private class ApplyException extends Exception {
        public boolean isAtTop;
        public boolean inConfigMode;

        public ApplyException(String msg,
                              boolean isAtTop, boolean inConfigMode) {
            super(msg);
            this.isAtTop = isAtTop;
            this.inConfigMode = inConfigMode;
            inConfig = inConfigMode;
        }
        public ApplyException(String line, String msg,
                              boolean isAtTop, boolean inConfigMode) {
            super("(command: "+line+"): "+msg);
            this.isAtTop = isAtTop;
            this.inConfigMode = inConfigMode;
            inConfig = inConfigMode;
        }
    }

    private void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        // apply one line at a time
        String lines[];
        String chunk;
        int i, n;
        boolean isAtTop=true;
        long time;
        long lastTime = System.currentTimeMillis();

        // we have entered config mode in initialize()

        lines = data.split("\n");
        LOGGER.info("applyConfig() length: " + Integer.toString(lines.length));

        try {
            for (i = 0; i < lines.length; i += 1000) {
                // Send chunk of 1000
                for (chunk = "", n = i; n < lines.length && n < (i + 1000); n++)
                    chunk = chunk + lines[n] + "\n";
                LOGGER.info("sending chunk("+i+")");
                session.print(chunk);

                // Check result of one line at the time
                for (n = i; n < lines.length && n < (i + 1000); n++) {
                    // Set a large timeout if needed
                    time = System.currentTimeMillis();
                    if ((time - lastTime) > (0.8 * readTimeout)) {
                        lastTime = time;
                        worker.setTimeout(readTimeout);
                    }
                    isAtTop = noprint_line_wait(worker, cmd, lines[n], 0);
                }
            }
        }
        catch (ApplyException e) {
            if (!e.isAtTop)
                moveToTopConfig();
            throw e;
        }

        // make sure we have exited from all submodes
        if (!isAtTop)
            moveToTopConfig();

        // Temporary - this should really be done in commit, but then
        // NCS should also be able to abort after commit.
        /*
             prepare (send data to device)
                 /   \
                v     v
             abort | commit(send confirmed commit (ios would do noop))
                      /   \
                     v     v
                 revert | persist (send confirming commit)
        */
        try {
            // FIXME: if there is only one device involved, we should
            // do normal commit instead.
            time = System.currentTimeMillis();
            if ((time - lastTime) > (0.8 * readTimeout)) {
                lastTime = time;
                worker.setTimeout(readTimeout);
            }
            print_line_wait(worker, cmd, "commit confirmed", 0);
        }
        catch (ApplyException e) {
            // if confirmed commit failed, invoke this special command
            // in order to figure out what went wrong
            String line = "show configuration failed";
            session.println(line);
            session.expect(line, worker);
            String msg = session.expect(prompt, worker);
            if (msg.indexOf("No such configuration") >= 0) {
                // this means there is no last failed error saved
                throw e;
            } else {
                throw new ApplyException(line, msg, e.isAtTop, e.inConfigMode);
            }
        }
    }

    public void initialize(NedWorker worker) throws Exception {
        // first take a lock
        if (enterConfig(worker, NedCmd.INITIALIZE)) {
            // if successful fetch transaction id if needed
            super.initialize(worker);
        }
    }

    public void uninitialize(NedWorker worker) throws Exception {
        // release the lock
        if (inConfig) {
            exitConfig();
        }
        super.uninitialize(worker);
    }

    // mangle output, or just pass through we're invoked during prepare phase
    // of NCS
    public void prepare(NedWorker worker, String data)
        throws Exception {

        session.setTracer(worker);

        applyConfig(worker, NedCmd.PREPARE_CLI, data);
        worker.prepareResponse();
    }

    public void prepareDry(NedWorker worker, String data)
        throws Exception {

        worker.prepareDryResponse(data);
    }

    public void abort(NedWorker worker, String data)
        throws Exception {

        session.setTracer(worker);

        print_line_wait_oper(worker, NedCmd.ABORT_CLI, "abort");
        inConfig = false;
        worker.abortResponse();
    }

    public void revert(NedWorker worker, String data)
        throws Exception {

        session.setTracer(worker);

        print_line_wait_oper(worker, NedCmd.REVERT_CLI, "abort");
        inConfig = false;
        worker.revertResponse();
    }

    public void commit(NedWorker worker, int timeout)
        throws Exception {
        session.setTracer(worker);

        // Temporary - see applyConfig above.
        /*
        if (timeout < 0) {
            print_line_wait(worker, NedCmd.COMMIT, "commit", 0);
            hasConfirming = false;
        } else if (timeout < 30) {
            print_line_wait(worker, NedCmd.COMMIT, "commit confirmed 30",
                            0);
            hasConfirming = true;
        } else {
            print_line_wait(worker, NedCmd.COMMIT, "commit confirmed "+
                            timeout, 0);
            hasConfirming = true;
        }
        */
        worker.commitResponse();
    }

    public void persist(NedWorker worker) throws Exception {
        session.setTracer(worker);

        // Temporary - see applyConfig above.
        //        if (hasConfirming)
        if (inConfig) {
            print_line_wait(worker, NedCmd.COMMIT, "commit", 0);
            exitConfig();
        }
        worker.persistResponse();
    }

    public void close(NedWorker worker)
        throws Exception {
        if (session != null) {
            session.setTracer(worker);
            session.close();
        }
        if (connection != null)
            connection.close();
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
    }

    public void close() {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        try {
            if (session != null) {
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
        session.setTracer(worker);

        // calculate checksum of config
        int i;

        if (inConfig) {
            session.print("do show running-config\n");
            session.expect("do show running-config", worker);
        }
        else {
            session.print("show running-config\n");
            session.expect("show running-config", worker);
        }

        String res = session.expect(prompt, worker);

        i = res.indexOf("Building configuration...");
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

        byte[] bytes = res.getBytes("UTF-8");
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] thedigest = md.digest(bytes);
        BigInteger md5Number = new BigInteger(1, thedigest);
        String md5String = md5Number.toString(16);

        worker.getTransIdResponse(md5String);

        return;
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

    private static int indexOf(Pattern pattern, String s, int start) {
        Matcher matcher = pattern.matcher(s);
        return matcher.find(start) ? matcher.start() : -1;
    }

    public void show(NedWorker worker, String toptag)
        throws Exception {
        session.setTracer(worker);
        int i;

        if (toptag.equals("interface")) {
            session.print("show running-config\n");
            session.expect("show running-config", worker);

            String res = session.expect(prompt, worker);

            i = res.indexOf("Building configuration...");
            if (i >= 0) {
                int n = res.indexOf("\n", i);
                res = res.substring(n+1);
            }

            i = res.indexOf("!! Last configuration change:");
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

            // cut everything between boot-start-marker and boot-end-marker
            i = res.indexOf("boot-start-marker");
            if (i >= 0) {
                int x = res.indexOf("boot-end-marker");
                if (x > i) {
                    int n = res.indexOf("\n", x);
                    res = res.substring(0,i)+res.substring(n+1);
                }
            }

            // remove archive
            i = res.indexOf("\narchive");
            if (i >= 0) {
                int x = res.indexOf("\n!", i);
                if (x >= 0) {
                    res = res.substring(0,i)+res.substring(x);
                }
                else {
                    res = res.substring(0,i);
                }
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

            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since the Iosxr
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
                                int connectTimeout, // msec
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
        session.setTracer(worker);

        worker.error(NedCmd.CMD,
                     NedCmd.cmdToString(NedCmd.CMD),
                     "not implemented");
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
        return new IosxrNedCli(device_id,
                               ip, port, proto, ruser, pass, secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }

    public String toString() {
        if (ip == null)
            return device_id+"-<ip>:"+Integer.toString(port)+"-"+proto;
        return device_id+"-"+ip.toString()+":"+
            Integer.toString(port)+"-"+proto;
    }
}

