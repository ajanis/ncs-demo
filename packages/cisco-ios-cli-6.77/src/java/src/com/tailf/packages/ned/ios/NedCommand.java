package com.tailf.packages.ned.ios;

import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import com.tailf.packages.ned.nedcom.NedProgress;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStart;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStop;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.tailf.ned.NedCmd;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.SSHSessionException;

import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;


/**
 * NedCommand
 *
 */
@SuppressWarnings("deprecation")
public class NedCommand {

    // Prompts
    private static final String CMD_ERROR = "xyzERRORxyz";
    private static final int PROMPT_EXEC = 1;
    private static final int PROMPT_HELP = 2;

    /*
     * Constructor data
     */
    private IOSNedCli owner;
    private String execPrefix;
    private String configPrefix;
    private String execPrompt;
    private String configPrompt;
    private String errorPrompt;
    private String[][] defaultPrompts;

    /*
     * Local data
     */
    private ArrayList<String[]> cmdStrings;
    private Pattern[] cmdPatterns;

    /*
     * State data
     */
    private boolean configMode;
    private boolean rebooting;
    private String currentExecPrompt;
    private boolean silent;


    /*
     **************************************************************************
     * Constructor
     **************************************************************************
     */

    /**
     * Constructor
     * @param owner - Parent class
     */
    NedCommand(IOSNedCli owner,
               String execPrefix, String configPrefix,
               String execPrompt, String configPrompt,
               String errorPrompt, String[][] defaultPrompts) throws NedException {
        this.owner = owner;
        this.execPrefix = execPrefix;
        this.configPrefix = configPrefix;
        this.execPrompt = execPrompt;
        this.configPrompt = configPrompt;
        this.errorPrompt = errorPrompt;
        this.defaultPrompts = defaultPrompts;
    }


    /*
     **************************************************************************
     * Public methods
     **************************************************************************
     */

    /**
     * Return command as a single string
     * @param
     * @throws Exception
     */
    public String prepare(NedWorker worker, String cmd, ConfXMLParam[] param) throws NedException {

        // State
        this.configMode = false;
        this.rebooting = false;
        this.silent = cmd.contains("any-hidden");

        String xml = "";
        if (param != null) {
            // Convert ConfXMLParam[] to XML string buffer
            try {
                xml = ConfXMLParam.toXML(param);
            } catch (Exception e) {
                throw new NedException("NedCommand.prepare() ERROR", e);
            }
            traceVerbose(worker, "command xml:\n"+ xml);

            // configMode
            Pattern p = Pattern.compile("<"+this.configPrefix+"[:]args xmlns");
            Matcher m = p.matcher(xml);
            if (m.find()) {
                this.configMode = true;
            }

            // Add optional command line arguments from 'args'
            if (!xml.trim().isEmpty()) {
                p = Pattern.compile("<\\S+args xmlns\\S+?>(.+?)</\\S+args>", Pattern.DOTALL);
                m = p.matcher(xml);
                while (m.find()) {
                    cmd += (" " + xmlTransformSpecialCharacters(m.group(1)));
                }
            }
        }

        //
        // Populate cmdStrings
        //
        this.cmdStrings = new ArrayList<>();

        // [1] - Device prompts
        this.currentExecPrompt = this.execPrompt;
        try {
            // Poll the device for its execPrompt to enable stricter matching
            String strictRegex = owner.nedSettings.getString("live-status/exec-strict-prompt");
            if (strictRegex != null) {
                this.currentExecPrompt = strictRegex;
                if (strictRegex.contains("%p")) {
                    traceInfo(worker, "live-status exec-strict-prompt "+stringQuote(strictRegex)
                                    +", retrieving device prompt");
                    owner.session.println("");
                    NedExpectResult res = owner.session.expect(new String[] { execPrompt },
                                                               true,owner.writeTimeout,worker);
                    this.currentExecPrompt = this.currentExecPrompt.replace("%p",
                                                                            Pattern.quote(owner.expectGetMatch(res)));
                }
                traceInfo(worker, "strict device exec prompt = "+stringQuote(this.currentExecPrompt));
            }
        } catch (Exception e) {
            throw new NedException("exec-strict-prompt failed to derive device prompt: "+e.getMessage(), e);
        }
        cmdStrings.add(new String[] { this.configPrompt, "<exit>" });
        cmdStrings.add(new String[] { this.currentExecPrompt, "<exit>" });
        cmdStrings.add(new String[] { "<<helpPrompt>>", "<exit>" }); // Temporary value

        // [2] - One-shot auto-prompts from command line action in XML syntax
        if (!xml.trim().isEmpty()) {
            Pattern p0 = Pattern.compile("<\\S+:auto-prompts xmlns\\S+?>(.+?)<\\/\\S+auto-prompts>", Pattern.DOTALL);
            Matcher m0 = p0.matcher(xml);
            while (m0.find()) {
                String[] newEntry = new String[2];

                // <question>
                Pattern p = Pattern.compile("<\\S+question>(.+)<\\/\\S+question>", Pattern.DOTALL);
                Matcher m = p.matcher(m0.group(1));
                if (!m.find()) {
                    throw new NedException("NedCommand.prepare() failed to extract 'question' in "
                                           +stringQuote(m0.group(1)));
                }
                newEntry[0] = xmlTransformSpecialCharacters(m.group(1));

                // <answer>
                p = Pattern.compile("<\\S+answer>(.+)<\\/\\S+answer>", Pattern.DOTALL);
                m = p.matcher(m0.group(1));
                if (m.find()) {
                    newEntry[1] = xmlTransformSpecialCharacters(m.group(1));
                } else {
                    newEntry[1] = null;
                }

                cmdStrings.add(newEntry);
            }
        }

        // [3] - ned-settings <ned-name> live-status auto-prompts
        List<Map<String,String>> entries = owner.nedSettings.getListEntries("live-status/auto-prompts");
        for (Map<String,String> entry : entries) {
            String[] newEntry = new String[2];
            String id = entry.get("__key__"); // "id"
            newEntry[0] = entry.get("question");
            if (!newEntry[0].endsWith("$")) {
                // Backwards compatibility fix when old API first had to match default questions (: or ]?)
                newEntry[0] += ".*";
            }
            newEntry[1] = entry.get("answer");
            if (newEntry[0] == null) {
                throw new NedException("missing 'live-status/auto-prompts{"+id+"}/question' ned-setting");
            }
            cmdStrings.add(newEntry);
        }

        // [4] Static default auto-prompts from NED
        for (int i = 0; i < defaultPrompts.length; i++) {
            cmdStrings.add(defaultPrompts[i]);
        }

        //
        // Populate cmdPatterns and log
        //
        cmdPatterns = new Pattern[cmdStrings.size()];
        StringBuilder log = new StringBuilder("command prompts:\n");
        for (int i = 0; i < cmdStrings.size(); i++) {
            String[] entry = cmdStrings.get(i);
            log.append("   ["+i+"] "+stringQuote(entry[0]));
            if (entry[1] != null) {
                log.append(" => "+stringQuote(entry[1]));
            }
            log.append("\n");
            cmdPatterns[i] = Pattern.compile(entry[0]);
        }
        traceVerbose(worker, log.toString());

        // Generic exec mode command(s) callpoint
        if (!this.configMode && cmd.startsWith("any ")) {
            cmd = cmd.substring(4);
        }
        if (this.configMode && cmd.startsWith("exec ")) {
            cmd = cmd.substring(5);
        }
        if (!this.configMode && cmd.startsWith("any-hidden ")) {
            cmd = cmd.substring(11);
        }
        traceVerbose(worker, "command args: "+stringQuote(cmd)+"\n");

        // Strip bad characters
        return commandWash(cmd);
    }


    /**
     * Run command(s) on device from action
     * From ncs_cli: devices device <dev> live-status exec any "command"
     * @param
     * @throws Exception
     */
    public void execute(NedWorker worker, String cmd) throws Exception {
        final long start = tick(0);

        String config = this.configMode ? "(config)" : "";
        traceInfo(worker, "BEGIN COMMAND "+config+"# "+stringQuote(cmd));
        NedProgress.Progress progress = reportProgressStart(owner, NedProgress.EXEC_ACTION);

        //
        // Config mode - default|exec
        //
        if (this.configMode) {
            owner.enterConfig(worker, null);
        }

        //
        // Run command(s) on device
        //
        StringBuilder replies = new StringBuilder();
        try {
            if (this.silent && owner.trace) {
                owner.traceInfo(worker, "Executing any-hidden action, temporarily disabling trace");
                owner.session.setTracer(null);
            }
            String[] cmds = cmd.split(" ; ");
            for (int i = 0 ; i < cmds.length ; i++) {
                String reply = doCommand(worker, cmds[i], cmds.length == 1);
                if (reply.startsWith(CMD_ERROR)) {
                    replies.append(reply.substring(CMD_ERROR.length()));
                    if (this.configMode) {
                        owner.exitConfig(worker, "command error");
                    }
                    traceInfo(worker, "DONE COMMAND "+tickToString(start));
                    reportProgressStop(progress, NedProgress.ERROR);
                    worker.error(NedCmd.CMD, replies.toString());
                    return;
                }
                replies.append(reply);
                if (this.configMode && owner.isExecError(reply)) {
                    break;
                }
            }
        } catch (Exception e) {
            reportProgressStop(progress, NedProgress.ERROR);
            throw e;
        } finally {
            if (this.silent && owner.trace) {
                owner.session.setTracer(worker);
            }
        }

        //
        // Report device output 'replies'
        //
        if (this.configMode) {
            owner.exitConfig(worker, "command");
        }
        owner.setReadTimeout(worker);
        traceInfo(worker, "DONE COMMAND "+tickToString(start));
        reportProgressStop(progress);
        if (this.configMode) {
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue(this.configPrefix, "result", new ConfBuf(replies.toString()))});
        } else {
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue(this.execPrefix, "result", new ConfBuf(replies.toString()))});
        }

        //
        // Rebooting
        //

        // [cisco-ios] issu runversion delay patch
        if (cmd.startsWith("issu runversion")
            && replies.indexOf("Initiating active RP failover") > 0) {
            this.rebooting = true;
        }

        // Rebooting
        if (this.rebooting) {
            traceInfo(worker, "Rebooting device...");
            owner.setWriteTimeout(worker);
            sleep(worker, 30 * (long)1000, true); // Sleep 30 seconds
        }
    }


    /**
     * Run single exec command on device from Java code
     * @param
     * @throws Exception
     */
    public String runCommand(NedWorker worker, String cmd) throws Exception {
        String output = doCommand(worker, prepare(worker, cmd, null), true);
        if (output.startsWith(CMD_ERROR)) {
            return output.substring(CMD_ERROR.length());
        }
        return output;
    }


    /*
     **************************************************************************
     * Private methods
     **************************************************************************
     */

    /**
     * Run a single command on device
     * @param
     * @return
     * @throws Exception
     */
    private String doCommand(NedWorker worker, String cmd, boolean single) throws Exception {
        boolean noprompts = false;
        String[] promptv = null;
        int promptc = 0;
        int i;

        // Show the command we sent
        StringBuilder reply = new StringBuilder();
        if (!single || owner.nedSettings.getBoolean("live-status/always-show-exec-command")) {
            reply.append("\n> " + cmd);
        }

        traceVerbose(worker, "doCommand("+stringQuote(cmd)+")");

        // Enable noprompts or extract answer(s) to prompting questions
        if (cmd.matches("^.+\\s*\\|\\s*noprompts\\s*$")) {
            noprompts = true;
            cmd = cmd.substring(0,cmd.lastIndexOf('|')).trim();
        } else {
            Pattern p = Pattern.compile("(.+)\\|\\s*prompts\\s+(\\S.*)", Pattern.DOTALL);
            Matcher m = p.matcher(cmd);
            if (m.find()) {
                cmd = m.group(1).trim();
                promptv = m.group(2).trim().split(" +");
            }
        }

        // Send command or help (ending with ?) to device
        boolean help = cmd.charAt(cmd.length() - 1) == '?';
        String helpPrompt = currentExecPrompt;
        String modebuf = this.configMode ? " (config): " : ": ";
        traceInfo(worker, "SENDING_CMD"+modebuf+stringQuote(cmd));

        final String simulated = owner.simulateShow(worker, cmd);
        if (simulated != null) {
            return simulated;
        } else if (cmd.trim().startsWith("CTRL-C")) {
            owner.session.print("\u0003");
            if (cmd.toLowerCase().contains("async")) {
                return "\nSent CTRL-C, ignoring any device output\n";
            }
            reply = new StringBuilder("\nSent CTRL-C\n");
            // Note: do not wait for command echo
        } else {
            if (help) {
                owner.session.print(cmd);
                helpPrompt = "\\A[^\\# ]+#[ ]*" + Pattern.quote(cmd.substring(0, cmd.length()-1)) + "[ ]*";
                traceVerbose(worker, "help-prompt = " + stringQuote(helpPrompt));
                noprompts = true;
            } else {
                owner.session.print(cmd+"\n");
            }

            // Wait for command echo from device
            StringBuilder echoReply = new StringBuilder();
            for (String wait: cmd.split("\n")) {
                traceVerbose(worker, "Waiting for command echo "+stringQuote(wait));
                NedExpectResult res = owner.session.expect(new String[] {
                        this.errorPrompt, Pattern.quote(wait)},
                    true, owner.writeTimeout, worker);
                echoReply.append(res.getText());
                if (res.getHit() == 0) {
                    return CMD_ERROR + echoReply.toString();
                }
            }
        }

        // Switch to use write-timeout
        owner.lastTimeout = owner.setWriteTimeout(worker);

        //
        // Command fastpath - show
        //
        if (!configMode && !help && cmd.startsWith("show ")) {
            traceInfo(worker, "Waiting for show prompt "+stringQuote(currentExecPrompt)
                      +" (write-timeout "+owner.writeTimeout+")");
            NedExpectResult res = owner.session.expect(new String[] { currentExecPrompt },
                                                       true, owner.writeTimeout, worker);
            return reply.toString() + res.getText();
        }

        //
        // Wait for prompt, answer prompting questions with | prompts info
        //
        cmdPatterns[PROMPT_HELP] = Pattern.compile(helpPrompt); // Update helpPrompt
        while (true) {
            // Update timeout
            owner.lastTimeout = owner.resetWriteTimeout(worker, owner.lastTimeout);

            traceInfo(worker, "Waiting for command reply (write-timeout "+owner.writeTimeout+")");
            NedExpectResult res = owner.session.expect(cmdPatterns, true, owner.writeTimeout, worker);
            String output = res.getText();
            reply.append(output);

            String[] entry = cmdStrings.get(res.getHit());
            traceInfo(worker, stringQuote(output)+" matched ["+res.getHit()+"] "+stringQuote(entry[0]));

            //
            // Matched <exit> - exit command
            //
            if ("<exit>".equals(entry[1])) {
                if (help) {
                    sendBackspaces(worker, cmd);
                }

                // Command exited config mode
                if (this.configMode && res.getHit() == PROMPT_EXEC) {
                    traceInfo(worker, "command ERROR: exited config mode calling '"+cmd+"'");
                    owner.inConfig = false;
                    owner.enterConfig(worker, null);
                    return CMD_ERROR + reply.toString() + "\nERROR: Aborted, last command left config mode";
                }

                // WARNING: No command error checks are performed, command is 100% raw.
                if (promptv != null && promptc < promptv.length) {
                    reply.append("\n(unused prompts:");
                    for (i = promptc; i < promptv.length; i++) {
                        reply.append(" "+promptv[i]);
                    }
                    reply.append(")");
                }

                break;
            }

            //
            // Matched <timeout> - reset read timeout pattern
            //
            else if ("<timeout>".equals(entry[1])) {
                owner.lastTimeout = owner.setWriteTimeout(worker);
                continue;
            }

            //
            // Default case, <ignore> or send answer
            //
            if (noprompts // '| noprompts' option
                || help
                || cmd.startsWith("show ")) {
                traceInfo(worker, "Ignoring output ["+res.getHit()+"] "+stringQuote(output));
                continue;
            }

            // Matched null|""|<ignore> - ignore output
            if (entry[1] == null || entry[1].isEmpty() || "<ignore>".equals(entry[1])) {
                traceInfo(worker, "<ignore>/missing answer -> continue parsing");
                continue;
            }

            // Retrieve answer from | prompts
            String answer = null;
            if ("<prompt>".equals(entry[1])) {
                if (promptv != null && promptc < promptv.length) {
                    // Get answer from command line, i.e. '| prompts <val>'
                    traceInfo(worker, "Using | prompts answer #"+promptc+" "+stringQuote(promptv[promptc]));
                    answer = promptv[promptc++];
                }
            } else {
                answer = entry[1];
                reply.append("(auto-prompt "+answer+") -> ");
            }

            // Missing answer to a question prompt:
            if (answer == null) {
                reply.append("\nMissing answer to a device question:\n+++" + reply.toString()
                             + "\n+++\nSet auto-prompts ned-setting or add '| prompts <answer(s)>'\n"
                             + "\nNote: Single letter <answer> is sent without LF. Use 'ENTER' for LF only."
                             + "\n      Add '| noprompts' in order to ignore all prompts.");
                owner.exitPrompting(worker);
                return CMD_ERROR + reply.toString();
            }

            // Send answer to device
            traceInfo(worker, "SENDING_CMD_ANSWER: "+answer);
            if ("ENTER".equals(answer) || "<enter>".equals(answer)) {
                owner.session.print("\n");
            } else if ("IGNORE".equals(answer) || "<ignore>".equals(answer)) {
                continue; // use to avoid blocking on bad prompts
            } else if (answer.length() == 1) {
                owner.session.print(answer);
            } else {
                owner.session.print(answer+"\n");
            }

            // Check if rebooting
            if (willRebootDevice(worker, cmd, output, answer)) {
                traceInfo(worker, "+++ Detected device reboot +++");
                this.rebooting = true;
                break;
            }
        }

        return reply.toString();
    }


    /**
     * Return true if command will reboot device
     * @param
     * @return
     */
    private boolean willRebootDevice(NedWorker worker, String cmd, String output, String answer) {

        traceVerbose(worker, "Checking if device will reboot: cmd="+stringQuote(cmd)+" output="
                     +stringQuote(output)+" answer="+stringQuote(answer));

        // reload
        if (cmd.startsWith("reload") // [cisco-ios]
            && output.contains("Proceed with reload")
            && answer.charAt(0) != 'n') {
            return true;
        }

        // controller-mode enable|disable
        if (cmd.startsWith("controller-mode ")
            && output.contains("Do you want to abort")
            && answer.toLowerCase().startsWith("n")) {
            return true;
        }

        return false;
    }


    /**
     * Convert XML Special characters
     * @param
     * @return
     */
    private String xmlTransformSpecialCharacters(String buf) {
        buf = buf.replace("&lt;", "<");
        buf = buf.replace("&gt;", ">");
        buf = buf.replace("&amp;", "&");
        buf = buf.replace("&quot;", "\"");
        buf = buf.replace("&apos;", "'");
        buf = buf.replace("&#13;", "\r");
        return buf;
    }


    /**
     * Send back spaces
     * @param
     * @throws Exceptions
     */
    private void sendBackspaces(NedWorker worker, String cmd) throws IOException, SSHSessionException {
        if (cmd.length() <= 1) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length() - 1; i++) {
            sb.append("\u0008"); // back space
        }
        traceVerbose(worker, "SENDING " + sb.length() + " backspace(s)");
        owner.session.print(sb.toString());
    }


    /**
     * Wash command from bad characters
     * @param
     * @return
     */
    private String commandWash(String cmd) {
        byte[] bytes = cmd.getBytes();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cmd.length(); ++i) {
            if (bytes[i] == 9) {
                continue;
            }
            if (bytes[i] == -61) {
                continue;
            }
            sb.append(cmd.charAt(i));
        }
        return sb.toString();
    }


    /**
     * Owner callback functions to write to trace
     * @param worker - NedWorker
     * @param info   - Info string to trace
     */
    private void traceInfo(NedWorker worker, String msg) {
        if (this.silent) {
            return;
        }
        owner.traceInfo(worker, msg);
    }
    private void traceVerbose(NedWorker worker, String msg) {
        if (this.silent) {
            return;
        }
        owner.traceVerbose(worker, msg);
    }
    private void traceDev(NedWorker worker, String msg) {
        if (this.silent) {
            return;
        }
        owner.traceDev(worker, msg);
    }


    /**
     * Millisecond sleep method
     * @param
     */
    private void sleep(NedWorker worker, long milliseconds, boolean log) {
        if (log) {
            traceVerbose(worker, "Sleeping " + milliseconds + " milliseconds");
        }
        try {
            Thread.sleep(milliseconds);
            if (log) {
                traceVerbose(worker, "Woke up from sleep");
            }
        } catch (InterruptedException e) {
            traceInfo(worker, "sleep interrupted");
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Simple tick utility used for performance meassurements.
     * @param t - current time
     * @return time passed
     */
    private long tick(long t) {
        return System.currentTimeMillis() - t;
    }


    /**
     * Print tick value in formatted syntax for logging
     * @param start - The tick
     * @return The formatted string containing tick value
     */
    private String tickToString(long start) {
        long stop = tick(start);
        return String.format("[%d ms]", stop);
    }

}
