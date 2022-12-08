package com.tailf.packages.ned.ios;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Random;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.log4j.Logger;

import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.maapi.Maapi;
import com.tailf.ncs.ResourceManager;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ned.NedCapability;
import com.tailf.ned.NedCliBase;
import com.tailf.ned.NedCliBaseTemplate;
import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedMux;
import com.tailf.ned.NedTTL;
import com.tailf.ned.NedTracer;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedWorker.TransactionIdMode;
import com.tailf.ned.SSHSessionException;

/**
 * This class implements NED interface for cisco ios routers
 *
 */

public class IOSNedCli extends NedCliBaseTemplate {
    public static Logger LOGGER  = Logger.getLogger(IOSNedCli.class);

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public  Maapi           mm;
    private enum dt { BASE, CATALYST, C3550, CAT4500E };
    private dt devicetype = dt.BASE;
    private final static String privexec_prompt, prompt;
    private final static Pattern[] plw, ec, ec2, config_prompt;

    static {
        // start of input, > 0 non-# and ' ', one #, >= 0 ' ', eol
        privexec_prompt = "\\A[^\\# ]+#[ ]?$";

        prompt = "\\A\\S*#";

        plw = new Pattern[] {
            //FIXME: Make sure prompt patterns begin with newline!?
            Pattern.compile("Continue\\?\\[confirm\\]"),
            Pattern.compile("\\A.*\\(cfg\\)#"),
            Pattern.compile("\\A.*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#"),
            Pattern.compile("\\A\\S.*#")
        };

        config_prompt = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#")
        };

        ec = new Pattern[] {
            Pattern.compile("Do you want to kill that session and continue"),
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

        ec2 = new Pattern[] {
            Pattern.compile("\\A.*\\(cfg\\)#"),
            Pattern.compile("\\A.*\\(config\\)#"),
            Pattern.compile("\\A.*\\(.*\\)#"),
            Pattern.compile("Aborted.*\n"),
            Pattern.compile("Error.*\n"),
            Pattern.compile("syntax error.*\n"),
            Pattern.compile("error:.*\n")
        };

    }

    public IOSNedCli() {
        super();
        try {
            ResourceManager.registerResources(this);
        } catch (Exception e) {
            LOGGER.error("Error injecting Resources", e);
        }
    }

    public IOSNedCli(String device_id,
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

        super(device_id, ip, port, proto, ruser, pass, secpass,
              trace, connectTimeout, readTimeout, writeTimeout, mux,
              worker);

        NedTracer tracer;

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
            NedExpectResult res;

            res = session.expect(new String[] {
                    "\\A[Ll]ogin:",
                    "\\A[Uu]sername:",
                    "\\A[Pp]assword:",
                    "\\A\\S.*>",
                    privexec_prompt},
                worker);
            if (res.getHit() < 3)
                throw new NedException("Authentication failed");
            if (res.getHit() == 3) {
                session.print("enable\n");
                res = session.expect(new String[] {"[Pp]assword:", prompt},
                                     worker);
                if (res.getHit() == 0) {
                    if (secpass == null || secpass.isEmpty())
                        throw new NedException("Secondary password "
                                               +"not set");
                    session.print(secpass+"\n"); // enter password here
                    try {
                        res = session.expect(new String[] {"\\A\\S*>", prompt},
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
            session.expect(prompt, worker);
            session.print("terminal width 0\n");
            session.expect(prompt, worker);
            trace(worker, "Requesting version string", "out");
            session.print("show version\n");
            String version = session.expect(prompt, worker);

            /* look for version string */

            try {
                trace(worker, "Inspecting version string", "out");
                if (version.indexOf("Cisco IOS Software") >= 0
                    // 3550 reports "show version" like this:
                    || version.indexOf("Cisco Internetwork Operating") >= 0) {
                    // found IOS
                    NedCapability capas[] = new NedCapability[1];
                    NedCapability statscapas[] = new NedCapability[1];

                    if (version.indexOf("C3550") >= 0) {
                        trace(worker, "Found C3550 device", "out");
                        devicetype = dt.C3550;
                    }
                    else if (version.indexOf("cat4500e") >= 0) {
                        trace(worker, "Found cat4500e device", "out");
                        devicetype = dt.CAT4500E;
                    }
                    else if (version.indexOf("Catalyst") >= 0) {
                        trace(worker, "Found Catalyst device", "out");
                        devicetype = dt.CATALYST;
                    } else {
                        trace(worker, "Found Cisco device", "out");
                        devicetype = dt.BASE;
                    }

                    capas[0] = new NedCapability(
                            "",
                            "urn:ios",
                            "tailf-ned-cisco-ios",
                            "",
                            "2014-02-12",
                            "");
                    statscapas[0] = new NedCapability(
                            "",
                            "urn:ios-stats",
                            "tailf-ned-cisco-ios-stats",
                            "",
                            "2014-02-12",
                            "");
                    setConnectionData(capas,
                                      statscapas,
                                      true,
                                      TransactionIdMode.UNIQUE_STRING);
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

    // Which Yang modules are covered by the class
    public String [] modules() {
        return new String[] { "tailf-ned-cisco-ios" };
    }

    // Which identity is implemented by the class
    public String identity() {
        return "ios-id:cisco-ios";
    }

    private void moveToTopConfig() throws IOException, SSHSessionException {
        NedExpectResult res;

        while(true) {
            session.print("exit\n");
            res = session.expect(config_prompt);
            if (res.getHit() == 0)
                return;
        }
    }

    private boolean isCliError(String reply) {
        String[] errprompt = {
            "error",
            "aborted",
            "exceeded",
            "invalid",
            "incomplete",
            "duplicate name",
            "may not be configured",
            "should be in range",
            "is used by",
            "being used",
            "cannot be deleted",
            "bad mask",
            "is not supported",
            "is not permitted",
            "cannot negate",
            "does not exist. create first",
            "use 'vrf forwarding' command for vrf",
            "use 'ip vrf forwarding' command for vrf",
            "is linked to a vrf. enable ipv4 on that vrf first",
            "failed"
        };

        if (reply.indexOf("hqm_tablemap_inform: CLASS_REMOVE error") >= 0)
            // 'error' when "no table-map <name>", but entry is removed
            return false;

        int size = errprompt.length;
        for (int n = 0; n < size; n++) {
            if (reply.toLowerCase().indexOf(errprompt[n]) >= 0)
                return true;
        }
        return false;
    }

    private boolean print_line_wait(NedWorker worker, int cmd, String line,
                                    int retrying, boolean waitForEcho)
        throws NedException, IOException, SSHSessionException, ApplyException {
        NedExpectResult res = null;
        boolean isAtTop;
        String lines[];

        session.print(line+"\n");
        if (waitForEcho)
            session.expect(new String[] { Pattern.quote(line) }, worker);
        res = session.expect(plw, worker);

        if (res.getHit() == 0) {
            // Received: "Continue?[confirm]"
            lines = res.getText().split("\n|\r");
            for(int i = 0 ; i < lines.length ; i++) {
                if (isCliError(lines[i])) {
                    throw new ApplyException(lines[i], false, false);
                }
            }
            // Send: "c" and wait for prompt
            session.print("c");
            res = session.expect(plw);
        }

        if (res.getHit() == 1 || res.getHit() == 2)
            isAtTop = true;
        else if (res.getHit() == 3)
            isAtTop = false;
        else
            throw new ApplyException("exited from config mode", false, false);

        lines = res.getText().split("\n|\r");
        for (int i = 0 ; i < lines.length ; i++) {
            if (isCliError(lines[i])) {
                throw new ExtendedApplyException(line, lines[i], isAtTop, true);
            }
            if (lines[i].toLowerCase().indexOf("is in use") >= 0 ||
             lines[i].toLowerCase().indexOf("wait for it to complete") >= 0 ||
                lines[i].toLowerCase().indexOf("already exists") >= 0) {
                // wait a while and retry
                if (retrying > 60) {
                    // already tried enough, give up
                    throw new ExtendedApplyException(line, lines[i], isAtTop,
                                                     true);
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

    private boolean enterConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException {
        NedExpectResult res = null;

        session.print("config t\n");
        res = session.expect(ec, worker);
        if (res.getHit() > 2) {
            worker.error(cmd, res.getText());
            return false;
        } else if (res.getHit() == 0) {
            session.print("yes\n");
            res = session.expect(ec2, worker);
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
            res = session.expect(new String[]
                {"\\A\\S*\\(config\\)#",
                 "\\A\\S*\\(cfg\\)#",
                 "\\A.*\\(.*\\)#",
                 "\\A\\S*\\(cfg.*\\)#",
                 prompt});
            if (res.getHit() == 4)
                return;
        }
    }

    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        // apply one line at a time
        String lines[];
        int i;
        boolean isAtTop=true;
        long time;
        long lastTime = System.currentTimeMillis();

        if (!enterConfig(worker, cmd))
            // we encountered an error
            return;

        try {
            lines = data.split("\n");
            for (i=0 ; i < lines.length ; i++) {
                time = System.currentTimeMillis();
                if ((time - lastTime) > (0.8 * writeTimeout)) {
                    lastTime = time;
                    worker.setTimeout(writeTimeout);
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
                else if (line.indexOf("certificate ") == 0) {
                    int start = line.indexOf("\"");
                    int end = line.indexOf("\"", start+1);
                    line = line.substring(0, start-1)+"\n"+
                        stringDequote(line.substring(start, end))+"\n";
                    waitForEcho = false;
                }
                else if (line.indexOf("disable passive-interface ") == 0) {
                    line = line.replaceAll("disable passive-interface ",
                                           "no passive-interface");
                } else if (devicetype == dt.CATALYST) {
                    // Catalyst style: policy-map / class / police
                    line = line.replaceAll("police (\\d+) bps (\\d+) byte",
                                           "police $1 $2");
                }

                // Send line
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

    private class ExtendedApplyException extends ApplyException {
        public ExtendedApplyException(String line, String msg,
                                      boolean isAtTop,
                                      boolean inConfigMode) {
            super("command: "+line+": "+msg, isAtTop, inConfigMode);
         }
    }

    public void close(NedWorker worker)
        throws NedException, IOException {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close(worker);
    }

    public void close() {
        try {
            ResourceManager.unregisterResources(this);
        } catch (Exception ignore) {
        }
        super.close();
    }

    public void getTransId(NedWorker worker)
        throws Exception {
        if (trace)
            session.setTracer(worker);

        // calculate checksum of config
        int i;

        session.print("show running-config\n");
        session.expect("show running-config", worker);

        String res = session.expect(privexec_prompt, worker);
        worker.setTimeout(readTimeout);

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

        // strip out all alias
        i = res.indexOf("\nalias");
        while(i >= 0) {
            int n = res.indexOf("\n", i+1);
            res = res.substring(0, i+1)+res.substring(n+1);
            i = res.indexOf("\nalias");
        }

        // strip out all macros
        i = res.indexOf("\nmacro name");
        while(i >= 0) {
            int n = res.indexOf("\n@", i);
            res = res.substring(0, i+1)+res.substring(n+2);
            i = res.indexOf("\nmacro name");
        }

        // strip out all cryptos
        i = res.indexOf("\ncrypto pki trustpoint");
        while(i >= 0) {
            int n = res.indexOf("!", i);
            res = res.substring(0, i+1)+res.substring(n+1);
            i = res.indexOf("\ncrypto pki trustpoint");
        }

        // strip out all cryptos
        //FIXME: BUG WITH cisco-ios-3550-0.txt config
        i = res.indexOf("\ncrypto pki certificate");
        while(i >= 0) {
            int n = res.indexOf("quit", i);
            n = res.indexOf("\n", n+1);
            res = res.substring(0, i+1)+res.substring(n+1);
            i = res.indexOf("\ncrypto pki certificate");
        }

        // strip out all ntp clock-period, not really someting
        // that is configurable
        i = res.indexOf("\nntp clock-period");
        while(i >= 0) {
            int n = res.indexOf("\n", i+1);
            res = res.substring(0, i+1)+res.substring(n+1);
            i = res.indexOf("\nntp clock-period");
        }

        i = res.indexOf("\nlicense");
        while(i >= 0) {
            int n = res.indexOf("\n", i+1);
            res = res.substring(0, i+1)+res.substring(n+1);
            i = res.indexOf("\nlicense");
        }

        i = res.indexOf("\nhw-module");
        while(i >= 0) {
            int n = res.indexOf("\n", i+1);
            res = res.substring(0, i+1)+res.substring(n+1);
            i = res.indexOf("\nhw-module");
        }

        // remove all between boot-start-marker and boot-end-marker
        i = res.indexOf("boot-start-marker");
        if (i >= 0) {
            int n = res.indexOf("boot-end-marker", i);
            if (n >= 0) {
                n = res.indexOf("\n", n);
                res = res.substring(0,i)+res.substring(n+1);
            } else {
                n = res.indexOf("\n", i);
                res = res.substring(0,i)+res.substring(n+1);
            }
        }

        // look for etype and convert to compact syntax
        i = res.indexOf("etype ");
        while(i >= 0) {
            int n = res.indexOf("\n", i);
            String estr = res.substring(i, n);
            estr = estr.replaceAll(" , ", ",");
            res = res.substring(0,i)+estr+res.substring(n);
            i = res.indexOf("etype ", i+5);
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

        res = res.replaceAll("channel-misconfig \\(STP\\)",
                             "channel-misconfig");

        res = res.replaceAll("no passive-interface ",
                             "disable passive-interface ");

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
        if (trace)
            session.setTracer(worker);

        int i;

        if (toptag.equals("interface")) {
            session.print("show running-config\n");
            session.expect("show running-config");

            String res = session.expect(privexec_prompt, worker);
            worker.setTimeout(readTimeout);

            // res=res.replaceAll("\\r", "");
            //System.err.println("SHOW_BEFORE=\n"+res);

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

            // strip out all alias
            i = res.indexOf("\nalias");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\nalias");
            }

            i = res.indexOf("\ndiagnostic");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\ndiagnostic");
            }

            i = res.indexOf("\nhw-module");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\nhw-module");
            }

            // strip out all macros
            i = res.indexOf("\nmacro name");
            while(i >= 0) {
                int n = res.indexOf("\n@", i);
                res = res.substring(0, i+1)+res.substring(n+2);
                i = res.indexOf("\nmacro name");
            }

            Pattern instancePattern = Pattern.compile("instance [0-9]+ vlan");
            i = indexOf(instancePattern, res, 0);
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                String vlans = res.substring(i, n);
                vlans = vlans.replaceAll(", ", ",");
                res = res.substring(0, i)+vlans+res.substring(n);
                i = indexOf(instancePattern, res, i+15);
            }

            i = res.indexOf("switchport trunk allowed vlan add");
            while(i >= 0) {
                int n = res.lastIndexOf("\n", i);
                res = res.substring(0,n)+","+res.substring(i+34);
                i = res.indexOf("switchport trunk allowed vlan add", i);
            }

            // strip out all ntp clock-period, not really someting
            // that is configurable
            i = res.indexOf("\nntp clock-period");
            while(i >= 0) {
                int n = res.indexOf("\n", i+1);
                res = res.substring(0, i+1)+res.substring(n+1);
                i = res.indexOf("\nntp clock-period");
            }

            // remove all between boot-start-marker and boot-end-marker
            i = res.indexOf("boot-start-marker");
            if (i >= 0) {
                int n = res.indexOf("boot-end-marker", i);
                if (n >= 0) {
                    n = res.indexOf("\n", n);
                    res = res.substring(0,i)+res.substring(n+1);
                } else {
                    n = res.indexOf("\n", i);
                    res = res.substring(0,i)+res.substring(n+1);
                }
            }

            // look for etype and convert to compact syntax
            i = res.indexOf("etype ");
            while(i >= 0) {
                int n = res.indexOf("\n", i);
                String estr = res.substring(i, n);
                estr = estr.replaceAll(" , ", ",");
                res = res.substring(0,i)+estr+res.substring(n);
                i = res.indexOf("etype ", i+5);
            }

            // look for certs and process separately
            i = res.indexOf ("\n certificate ");
            if (i >= 0) {
                int n = res.indexOf("\n", i+1);
                int quit = res.indexOf("quit", n);
                int quit_n = res.indexOf("\n", quit);
                String cert = stringQuote(res.substring(n, quit_n-1));
                res = res.substring(0,n-1)+" "+cert+res.substring(quit_n);
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

            res = res.replaceAll("channel-misconfig \\(STP\\)",
                                 "channel-misconfig");

            res = res.replaceAll("no passive-interface ",
                                 "disable passive-interface ");

            // Line by line 'policy-map/class/police' string replacement
            i = res.indexOf(" police ");
            while (i >= 0) {
                int n = res.indexOf("\n", i+8);
                String line = res.substring(i+1,n);
                line = line.trim();
                if (line.indexOf("police cir ") == 0 ||
                    line.indexOf("police rate ") == 0 ||
                    line.indexOf("police aggregate ") == 0) {
                    // Ignore these police lines, no transform needed
                }
                else if (line.matches("police (\\d+) bps (\\d+) byte.*")) {
                    // Ignore bps&byte (Catalyst) entries
                } else if (devicetype != dt.C3550) {
                    // Insert missing [cir|bc|be]
                    line = line.replaceAll("police (\\d+) (\\d+) (\\d+)",
                                           "police cir $1 bc $2 be $3");
                    line = line.replaceAll("police (\\d+) (\\d+)",
                                           "police cir $1 bc $2");
                    line = line.replaceAll("police (\\d+)",
                                            "police cir $1");
                    res = res.substring(0,i+1) + line + res.substring(n);
                }
                i = res.indexOf(" police ", n+1);
            }

            // Respond with updated show buffer
            // System.err.println("SHOW_AFTER=\n"+res);
            worker.showCliResponse(res);
        } else {
            // only respond to first toptag since the IOS
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

    private String ConfObjectToIfName(ConfObject kp) {
        String name = kp.toString();
        name = name.replaceAll("\\{", "");
        name = name.replaceAll("\\}", "");
        name = name.replaceAll(" ", "");
        return name;
    }

    public void showStats(NedWorker worker, int th, ConfPath path)
        throws Exception {

        mm.attach(th, -1, 1);

        System.err.println("showStats() "+path);

        Maapi m = mm;

        ConfObject[] kp = path.getKP();
        ConfKey x = (ConfKey) kp[1];
        ConfObject[] kos = x.elements();

        String root =
            "/ncs:devices/device{"+device_id+"}"
            +"/live-status/ios-stats:interfaces"+x;

        // Send show single interface command to device
        session.println("show interfaces "+ConfObjectToIfName(kp[1])+
                        " | include line|address");
        String res = session.expect("\\A.*#");

        // Parse single interface
        String[] lines = res.split("\r|\n");
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].indexOf("Invalid input detected") > 0)
                throw new NedException("showStats(): Invalid input");
            if (lines[i].indexOf("Hardware is") >= 0) {
                String[] tokens = lines[i].split(" +");
                for(int k=0 ; k < tokens.length-3 ; k++) {
                    if (tokens[k].equals("address") &&
                        tokens[k+1].equals("is")) {
                        m.setElem(th, tokens[k+2], root+"/mac-address");
                    }
                }
            }
            else if (lines[i].indexOf("Internet address is") >= 0) {
                String[] tokens = lines[i].split(" +");
                m.setElem(th, tokens[4], root+"/ip-address");
            }
        }

        worker.showStatsResponse(new NedTTL[] {
                new NedTTL(new ConfPath(root+"/ip-address"), 3),
                new NedTTL(new ConfPath(root+"/mac-address"), 3)
            });

        mm.detach(th);
    }

    public void showStatsList(NedWorker worker, int th, ConfPath path)
        throws Exception {

        System.err.println("showStatsList() "+path);

        ArrayList<NedTTL> ttls = new ArrayList<NedTTL>();

        mm.attach(th, -1, 1);

        String root =
            "/ncs:devices/device{"+device_id+"}"
            +"/live-status/ios-stats:interfaces";

        mm.delete(th, root);

        session.println("show interfaces | include line|address");
        String res = session.expect("\\A.*#");

        String[] lines = res.split("\r|\n");
        String currentInterfaceType = null;
        String currentInterfaceName = null;
        for(int i=0 ; i < lines.length ; i++) {
            if (lines[i].indexOf("line protocol") >= 0) {
                String[] tokens = lines[i].split(" +");
                Pattern pattern = Pattern.compile("\\d");
                Matcher matcher = pattern.matcher(tokens[0]);
                if (matcher.find()) {
                    currentInterfaceType =
                        tokens[0].substring(0,matcher.start());
                    currentInterfaceName =
                        tokens[0].substring(matcher.start());
                    mm.create(th, root+"{"+currentInterfaceType+
                              " "+currentInterfaceName+"}");
                }
            }
            if (currentInterfaceType != null &&
                lines[i].indexOf("Hardware is") >= 0) {
                String[] tokens = lines[i].split(" +");
                for(int x=0 ; x < tokens.length-3 ; x++) {
                    if (tokens[x].equals("address") &&
                        tokens[x+1].equals("is")) {
                        String epath =
                            root+"{"+currentInterfaceType+
                            " "+currentInterfaceName+"}"+"/mac-address";
                        mm.setElem(th, tokens[x+2], epath);
                        ttls.add(new NedTTL(new ConfPath(epath), 3));
                    }
                }
            }
            else if (currentInterfaceType != null &&
                     lines[i].indexOf("Internet address is") >= 0) {
                String[] tokens = lines[i].split(" +");
                String epath =
                    root+"{"+currentInterfaceType+" "+
                    currentInterfaceName+"}"+"/ip-address";
                mm.setElem(th, tokens[4], epath);
                ttls.add(new NedTTL(new ConfPath(epath), 3));
            }
        }

        worker.showStatsListResponse(60,
                                     ttls.toArray(new NedTTL[ttls.size()]));

        mm.detach(th);
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
        return new IOSNedCli(device_id,
                               ip, port, proto, ruser, pass, secpass, trace,
                               connectTimeout, readTimeout, writeTimeout,
                               mux, worker);
    }
}

