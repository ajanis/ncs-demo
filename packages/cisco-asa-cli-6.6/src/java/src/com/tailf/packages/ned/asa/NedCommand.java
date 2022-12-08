package com.tailf.packages.ned.asa;

import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.getMatch;

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
import com.tailf.conf.ConfObject;
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
    private static final int PROMPT_CONFIG = 0;
    private static final int PROMPT_EXEC = 1;
    private static final int PROMPT_HELP = 2;

    /*
     * Constructor data
     */
    private ASANedCli owner;
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
    private long lastExpect;

    /*
     * State data
     */
    private boolean configMode;
    private boolean rebooting;
    private String inputString;
    private String context;


    /*
     **************************************************************************
     * Constructor
     **************************************************************************
     */

    /**
     * Constructor
     * @param owner - Parent class
     */
    NedCommand(ASANedCli owner,
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
        this.inputString = null;
        this.context = null;

        String xml = "";
        if (param != null) {
            // Convert ConfXMLParam[] to XML string buffer
            try {
                xml = ConfXMLParam.toXML(param);
            } catch (Exception e) {
                throw new NedException("NedCommand.prepare() ERROR", e);
            }
            traceVerbose(worker, "COMMAND XML:\n"+ xml);

            // configMode
            Pattern p = Pattern.compile("<"+this.configPrefix+"[:]args xmlns");
            Matcher m = p.matcher(xml);
            if (m.find()) {
                this.configMode = true;
            }

            // context
            p = Pattern.compile("<\\S+context xmlns\\S+?>(.+?)</\\S+context>");
            m = p.matcher(xml);
            if (m.find()) {
                if (!owner.isAdminContext()) {
                    throw new NedException("context changed denied, non-admin user");
                }
                this.context = xmlTransformSpecialCharacters(m.group(1));
            }

            // input-string
            p = Pattern.compile("<\\S+input-string xmlns\\S+?>(.+?)</\\S+input-string>", Pattern.DOTALL);
            m = p.matcher(xml);
            if (m.find()) {
                this.inputString = xmlTransformSpecialCharacters(m.group(1));
                traceVerbose(worker, "COMMAND input-string: "+stringQuote(inputString));
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
        cmdStrings.add(new String[] { this.configPrompt, "<exit>" });
        cmdStrings.add(new String[] { this.execPrompt, "<exit>" });
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
        String log = "COMMAND prompts:\n";
        for (int i = 0; i < cmdStrings.size(); i++) {
            String[] entry = cmdStrings.get(i);
            log += ("   ["+i+"] "+stringQuote(entry[0]));
            if (entry[1] != null) {
                log += (" => "+stringQuote(entry[1]));
            }
            log += "\n";
            cmdPatterns[i] = Pattern.compile(entry[0]);
        }
        traceVerbose(worker, log);

        // Strip callpoint action name 'any' or 'exec'
        if (!this.configMode && cmd.startsWith("any ")) {
            cmd = cmd.substring(4);
        }
        if (this.configMode && cmd.startsWith("exec ")) {
            cmd = cmd.substring(5);
        }
        traceVerbose(worker, "COMMAND args: "+stringQuote(cmd)+"\n");

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
        String context = this.context != null ? ("/"+this.context) : "";
        logInfo(worker, "BEGIN COMMAND "+config+context+"# "+stringQuote(cmd));

        //
        // Specified context
        //
        if (this.context != null) {
            owner.changeto_context(worker, this.context, true);
        }

        //
        // Config mode - exec
        //
        if (this.configMode) {
            owner.enterConfig(worker, NedCmd.CMD, false);
        }

        //
        // Run command(s) on device
        //
        String replies = "";
        String[] cmds = cmd.split(" ; ");
        for (int i = 0 ; i < cmds.length ; i++) {
            String reply = doCommand(worker, cmds[i], cmds.length == 1);
            if (reply.startsWith(CMD_ERROR)) {
                replies += reply.substring(CMD_ERROR.length());
                if (this.configMode) {
                    owner.exitConfig(worker);
                }
                logInfo(worker, "DONE COMMAND "+owner.tickToString(start));
                worker.error(NedCmd.CMD, replies);
                return;
            }
            replies += reply;
        }

        //
        // Report device output 'replies'
        //
        if (this.configMode) {
            owner.exitConfig(worker);
        }
        owner.setReadTimeout(worker);
        logInfo(worker, "DONE COMMAND "+owner.tickToString(start));
        if (this.configMode) {
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue(this.configPrefix, "result", new ConfBuf(replies))});
        } else {
            worker.commandResponse(new ConfXMLParam[] {
                    new ConfXMLParamValue(this.execPrefix, "result", new ConfBuf(replies))});
        }

        //
        // Rebooting
        //
        if (this.rebooting) {
            logInfo(worker, "Rebooting device...");
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
        return doCommand(worker, prepare(worker, cmd, null), true);
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
        String reply = single ? "" : ("\n> " + cmd);

        traceVerbose(worker, "doCommand("+stringQuote(cmd)+")");

        // Check simulated command
        String simulated = owner.simulateCommand(worker, cmd);
        if (simulated != null) {
            return simulated;
        }

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

        // Reset timeout
        long lastTime = owner.setWriteTimeout(worker);

        // Send command or help (ending with ?) to device
        boolean help = cmd.charAt(cmd.length() - 1) == '?';
        String helpPrompt;
        String modebuf = this.configMode ? " (config): " : ": ";
        traceInfo(worker, "SENDING_CMD"+modebuf+stringQuote(cmd));
        if (help) {
            owner.session.print(cmd);
            helpPrompt = "\\A[^\\# ]+#[ ]*" + cmd.substring(0, cmd.length()-1) + "[ ]*";
            traceVerbose(worker, "help-prompt = " + stringQuote(helpPrompt));
            noprompts = true;
        }
        else {
            sessionPrintlnSlow(worker, cmd, false);
            helpPrompt = execPrompt;
        }
        cmdPatterns[PROMPT_HELP] = Pattern.compile(helpPrompt); // Update helpPrompt

        // Wait for command echo from device
        String echoReply = "";
        NedExpectResult res;
        for (String wait: cmd.split("\n")) {
            traceVerbose(worker, "Waiting for command echo "+stringQuote(wait));
            res = owner.session.expect(new String[] {this.errorPrompt, Pattern.quote(wait)}, worker);
            echoReply += res.getText();
            if (res.getHit() == 0) {
                return CMD_ERROR + echoReply;
            }
        }

        // Wait for prompt, answer prompting questions with | prompts info
        while (true) {
            // Update timeout
            lastTime = owner.resetWriteTimeout(worker, lastTime);

            traceInfo(worker, "Waiting for command reply (write-timeout "+owner.writeTimeout+" ms)");
            this.lastExpect = System.currentTimeMillis();
            try {
                res = owner.session.expect(cmdPatterns, true, owner.writeTimeout, worker);
            } catch (Exception e) {
                throw new Exception(timeoutToString(worker, cmdPatterns), e);
            }
            String output = res.getText();
            reply += output;

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
                    owner.enterConfig(worker, NedCmd.CMD, false);
                    return CMD_ERROR + reply + "\nERROR: Aborted, last command left config mode";
                }

                // WARNING: No command error checks are performed, command is 100% raw.
                if (promptv != null && promptc < promptv.length) {
                    reply += "\n(unused prompts:";
                    for (i = promptc; i < promptv.length; i++) {
                        reply += " "+promptv[i];
                    }
                    reply += ")";
                }

                break;
            }

            //
            // Matched <timeout> - reset read timeout pattern
            //
            else if ("<timeout>".equals(entry[1])) {
                lastTime = owner.setWriteTimeout(worker);
                continue;
            }

            //
            // Default case, <ignore> or send answer
            //
            if (noprompts // '| noprompts' option
                || help
                || cmd.contains(" /noconfirm")
                || cmd.startsWith("show ") || cmd.startsWith("packet-tracer ")) {
                traceInfo(worker, "Ignoring output ["+res.getHit()+"] "+stringQuote(output));
                continue;
            }

            // Matched null|""|<ignore> - ignore output
            if (entry[1] == null || entry[1].isEmpty() || "<ignore>".equals(entry[1])) {
                traceInfo(worker, "<ignore>/missing answer -> continue parsing");
                continue;
            }

            // Send input-string
            if (getMatch(output, "(End with the word .*on a line by itself)") != null) {
                if (this.inputString == null) {
                    reply = "\nMissing input-string option in:\n+++" + reply;
                    owner.session.print("quit\n");
                    break;
                }
                traceInfo(worker, "Sending input-string + quit:");
                String sent = this.inputString + "\nquit\n";
                owner.session.print(sent);

                // Wait for input-string echo(s)
                for (String wait: sent.split("\n")) {
                    traceVerbose(worker, "Waiting for command echo "+stringQuote(wait));
                    res = owner.session.expect(new String[] { this.errorPrompt, Pattern.quote(wait.trim())},
                                               true, owner.writeTimeout, worker);
                    if (res.getHit() == 0) {
                        return CMD_ERROR + res.getText();
                    }
                }
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
                reply += "(auto-prompt "+answer+") -> ";
            }

            // Missing answer to a question prompt:
            if (answer == null) {
                reply = "\nMissing answer to a device question:\n+++" + reply
                    + "\n+++\nSet auto-prompts ned-setting or add '| prompts <answer(s)>'\n"
                    + "\nNote: Single letter <answer> is sent without LF. Use 'ENTER' for LF only."
                    + "\n      Add '| noprompts' in order to ignore all prompts.";
                owner.exitPrompting(worker);
                return CMD_ERROR + reply;
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
                sessionPrintlnSlow(worker, answer, false);
            }

            // Check if rebooting
            if (cmd.startsWith("reload") // [cisco-ios]
                && output.contains("Proceed with reload")
                && answer.charAt(0) != 'n') {
                this.rebooting = true;
                break;
            }
        }

        return reply;
    }


    /**
     * Send command + newline to device. Delay with newline if configured
     * @param
     * @return
     */
    private void sessionPrintlnSlow(NedWorker worker, String line, boolean waitForEcho)
        throws IOException, SSHSessionException {
        if (owner.writeSlowNewlineDelay > 0) {
            owner.session.print(line);
            if (waitForEcho) {
                owner.session.expect(new String[] { Pattern.quote(line) }, worker);
            }
            sleep(worker, owner.writeSlowNewlineDelay, true);
            traceVerbose(worker, "Sending newline");
            owner.session.print("\n");
        } else {
            owner.session.print(line + "\n");
            if (waitForEcho) {
                owner.session.expect(new String[] { Pattern.quote(line) }, worker);
            }
        }
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
     * Retrieve all output in session
     * @param
     * @return
     * @throws NedException
     */
    private String timeoutToString(NedWorker worker, Pattern[] patterns) {
        String out = ", no response from device";
        try {
            Pattern[] all = new Pattern[] { Pattern.compile(".*", Pattern.DOTALL) };
            NedExpectResult res = owner.session.expect(all, true, 0);
            String matchbuf = owner.expectGetMatch(res);
            if (matchbuf != null && !matchbuf.trim().isEmpty()) {
                out = ", blocked on: " + stringQuote(matchbuf);
            }
            logError(worker, "\"expect\" pattern(s):");
            for (Pattern p : patterns) {
                logError(worker, " # Pattern: " + stringQuote(p.toString()));
            }
        } catch (Exception ignore) {
            logError(worker, "Failed to flush session", ignore);
        }
        String time = Integer.toString((int)(System.currentTimeMillis()-lastExpect)/1000);
        return "Timeout after "+time+"s"+out;
    }


    /**
     * Send back spaces
     * @param
     * @throws Exception
     */
    private void sendBackspaces(NedWorker worker, String cmd) throws Exception {
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
        owner.traceInfo(worker, msg);
    }
    private void traceVerbose(NedWorker worker, String msg) {
        owner.traceVerbose(worker, msg);
    }
    private void logInfo(NedWorker worker, String msg) {
        owner.logInfo(worker, msg);
    }
    private void logError(NedWorker worker, String msg, Exception e) {
        owner.logError(worker, msg, e);
    }
    private void logError(NedWorker worker, String msg) {
        logError(worker, msg, null);
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


    private String tickToString(long start) {
        long stop = tick(start);
        return String.format("[%d ms]", stop);
    }

}
