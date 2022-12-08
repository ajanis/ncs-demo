package com.tailf.packages.ned.nexus;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tailf.ned.NedCmd;
import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.NedWorker;
import com.tailf.ned.SSHSessionException;

import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.NedCommonLib.NedState;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import com.tailf.packages.ned.nedcom.NedProgress;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStart;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgressStop;


/**
 * This class implements NED interface for Cisco Nexus devices.
 *
 */

@SuppressWarnings("deprecation")
class NexusCLIDevice extends NexusDevice {
    private static final String PROMPT = "\\A[^\\#\\$ ]+#[ ]?(?:\u001b\\[.+m)?$";
    private static final Pattern[] plw;
    private static final int PROMPT_PATTERN_INDEX = 4;
    static {
        // print_line_wait() pattern
        plw = new Pattern[] {
            Pattern.compile("\\A\\S*\\(config\\)#"),
            Pattern.compile("\\A\\S*\\(conf.*\\)#"),
            Pattern.compile("\\A\\S*\\(cfg.*\\)#"),
            Pattern.compile(PROMPT),
            // Must send 'y' patterns: (PROMPT_PATTERN_INDEX)
            Pattern.compile("[Cc]ontinue\\?[ ]?\\[yes\\]"),
            Pattern.compile("[Cc]ontinue[\\? ]*\\((y|yes)/(n|no)\\)[\\? ]*(\\[(y|yes|n|no)\\])?"),
            Pattern.compile("[Cc]ontinue anyway\\? \\[yes\\]"),
            Pattern.compile("Are you sure\\? \\(y/n\\) +\\[(y|n)\\]"),
            Pattern.compile("Continue .*?\\(y/n\\)\\?\\s+\\[(yes|no)\\]"),
            Pattern.compile("[Pp]roceed\\s*\\??\\s*\\((y|yes)/(n|no)\\)\\?\\s+\\[(yes|no)\\]"),
            Pattern.compile("[Pp]roceed\\s*\\??\\s*\\[(y|yes)/(n|no)\\]\\s*\\[(yes|no|y|n)\\]"),
            Pattern.compile("\\s+\\(y/n\\)\\?\\s+\\[yes\\]")
        };
    }

    private List<String> lastFullConfig = null;
    private List<String> lastIfaceConfig = null;
    private List<String> lastIfaceFCConfig = null;
    private boolean useEchoDelim = true;

    public NexusCLIDevice(NexusNedCli ned) {
        super(ned);
    }

    @Override
    public void
    connect(NedWorker worker, NedSettings nedSettings)
        throws SSHSessionException, IOException, NedException {
        throw new NedException("BUG: legacy connect is no longer available");
    }

    @Override
    public String setup(NedWorker worker, NedSettings nedSettings) throws Exception {
        ned.logDebug(worker, "CLI SETUP ==>");

        /*
         * Configure terminal settings + show version and inventory
         */
        ned.session.print("terminal length 0 ; terminal width 511 ; show version ; show inventory\n");
        ned.session.expect(new String[] { Pattern.quote("terminal length") }, worker);
        String version = ned.session.expect(PROMPT, worker);

        if (version.indexOf("NETSIM") >= 0) {
            // For netsim
            ned.session.print("paginate false\n");
            ned.session.expect(PROMPT, worker);
        } else if ("auto".equals(nedSettings.getString("system-interface-defaults/handling"))) {
            ned.session.print("show running-config all | inc \"system default switchport\"\n");
            Set<String> systemDefaults = new HashSet<>();
            systemDefaults.addAll(Arrays.asList(ned.session.expect(PROMPT, worker).replaceAll("\r", "").split("\n")));
            if (systemDefaults.contains("no system default switchport")) {
                ned.defaultSwitchport = "false";
            } else {
                ned.defaultSwitchport = "true";
            }
            if (systemDefaults.contains("no system default switchport shutdown")) {
                ned.defaultSwitchportShutdown = "false";
            } else {
                ned.defaultSwitchportShutdown = "true";
            }
        }

        this.useEchoDelim = nedSettings.getBoolean("connection/use-echo-cmd-in-cli", true);

        /*
         * Look for version string
         */
        if (version.indexOf("Cisco Nexus Operating System") < 0) {
            throw new NedException("Not a Cisco Nexus device!");
        }

        ned.logDebug(worker, "CLI SETUP OK");
        return version;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void
    applyConfig(NedWorker worker, int cmd, String data)
        throws IOException, SSHSessionException, NedException {
        String[] lines;
        String line;
        int i;
        long time;
        long lastTime = System.currentTimeMillis();

        ned.logDebug(worker, "CLI APPLYCONFIG ==>");
        if (!enterConfig(worker, cmd)) {
            return;
        }

        lines = ned.modifyData(worker, data, NedState.APPLY);

        NedProgress.Progress progress = reportProgressStart(ned, "sending config");
        StringBuilder multiLine = null;
        try {
            worker.setTimeout(ned.readTimeout);
            boolean verifySTVlan = ned.hasBehaviour("verify-spanning-tree-vlan") && !ned.isDevice("netsim"); // ignore in netsim

            for (i = 0; i < lines.length; i++) {
                time = System.currentTimeMillis();
                if ((time - lastTime) > (0.8 * ned.readTimeout)) {
                    lastTime = time;
                    worker.setTimeout(ned.readTimeout);
                }

                line = lines[i];
                if ("! MULTI_LINE_BANNER START".equals(line)) {
                    ned.logVerbose(worker, "Sending multi-line-banner, no-echo");
                    // Send command line + newline and wait for prompt
                    multiLine = new StringBuilder();
                    continue;
                } else if ("! MULTI_LINE_BANNER END".equals(line)) {
                    if (multiLine == null) {
                        throw new NedException("BUG: got MULTI_LINE_BANNER END without START");
                    }
                    line = multiLine.toString();
                    multiLine = null;
                    waitForEcho = false;
                } else if (multiLine != null) {
                    multiLine.append(line);
                    multiLine.append("\n");
                    continue;
                }

                line = ned.modifyLine(worker, line);
                if (line == null) {
                    continue;
                }

                // Send line to device
                print_line_wait(worker, cmd, line.trim(), 0);

                if (verifySTVlan) {
                    Pattern pat = Pattern.compile("^((?:no )?)spanning-tree vlan ([0-9\\-, ]+)(.*)$");
                    Matcher m = pat.matcher(line);
                    if (m.find() &&
                        ((m.group(1).length() == 0) || (m.group(3).length() == 0))) {
                        String show = String.format("show running-config | inc \"^%sspanning-tree vlan .*%s\"",
                                                    m.group(1), m.group(3));
                        String subRange = m.group(2);
                        ned.session.print(show+"\n");
                        ned.session.expect(new String[] { Pattern.quote(show) }, worker);
                        String actual = ned.session.expect(plw, worker).getText();
                        m = pat.matcher(actual.trim());
                        boolean rangeAppliedOK = false;
                        String totalRange = "";
                        if (m.find()) {
                            totalRange = m.group(2);
                            rangeAppliedOK = ned.isRangeIncluded(subRange, totalRange);
                        }
                        if (!rangeAppliedOK) {
                            throw new CLIApplyException(line, String.format("device didn't report faulty range (ned sent: '%s', device reports: '%s')", subRange, totalRange), true, true);
                        }
                    }
                }

                waitForEcho = true;

                // Optional delay, used e.g. to not overload link/device
                int delay = cliDelay(line);
                if (delay < 0) {
                    delay = ned.deviceOutputDelay;
                }
                if (delay > 0) {
                    sleep(worker, delay, false);
                }
            }
            reportProgressStop(progress);

        } catch (CLIApplyException e) {
            reportProgressStop(progress, "error");
            if (e.inConfigMode) {
                exitConfig();
            }
            throw new NedException(e.getMessage());
        }

        exitConfig();
        ned.logDebug(worker, "CLI APPLYCONFIG OK");
    }

    @Override
    public void
    saveConfig(NedWorker worker)
        throws NedException, IOException, SSHSessionException {
        ned.logDebug(worker, "CLI SAVECONFIG ==>");
        String cmd = "copy running-config startup-config";
        ned.session.print(cmd + "\n");
        ned.session.expect(cmd, worker);
        ned.session.expect(PROMPT, worker);
        ned.logDebug(worker, "CLI SAVECONFIG OK");
    }

    @Override
    public String
    getConfig(NedWorker worker) throws Exception {
        ned.logDebug(worker, "CLI GETCONFIG ==>");
        StringBuilder res = new StringBuilder();
        boolean isNetsim = ned.isDevice("netsim");
        boolean useDiff = isNetsim ? false : ned.hasBehaviour("use-show-diff");

        try {
            worker.setTimeout(ned.readTimeout);
            String endDelim = (isNetsim || !useEchoDelim) ? "" : " ; echo __end_of_ned_cmd__";
            String exclStr = useDiff ? " | exclude Time:" : "";
            final String showCmd = String.format("show running-config%s%s", endDelim, exclStr);

            lastFullConfig = updateConfig(lastFullConfig,
                                          showCmd, worker, useDiff);
        } catch (NedException e) {
            if (useDiff) {
                ned.logError(worker, "NexusCLIDevice::getConfig: " + e.getMessage() + ", disabling 'use-show-diff'");
                ned.disableBehaviour("use-show-diff");
                return getConfig(worker);
            } else {
                throw e;
            }
        }

        for (String l : lastFullConfig) {
            res.append(l);
            res.append("\n");
        }

        if (ned.hasBehaviour("show-class-map-all")) {
            res.append("! PARSER_EXIT_TO_TOP\n");
            List<String> allClassMaps = updateConfig(null, "show running-config all | sec class-map", worker, false);
            for (String l : allClassMaps) {
                res.append(l);
                res.append("\n");
            }
        }

        if (res.indexOf("vpc domain ") >= 0) {
            res.append("! PARSER_EXIT_TO_TOP\n");
            List<String> vpcConfig = updateConfig(null, "show running-config vpc all | sec \"vpc domain\"", worker, false);
            for (String l : vpcConfig) {
                res.append(l);
                res.append("\n");
            }
        }

        if (ned.hasBehaviour("lldp-tlv-select-support")) {
            res.append("! PARSER_EXIT_TO_TOP\n");
            List<String> lldpTLVSelect = updateConfig(null, "show running-config all | inc \"^lldp tlv-select.*\"", worker, false);
            for (String l : lldpTLVSelect) {
                res.append(l);
                res.append("\n");
            }
        }

        if (ned.hasBehaviour("buggy-snmp-traps-quirk")) {
            res.append("! PARSER_EXIT_TO_TOP\n");
            List<String> snmpTraps = updateConfig(null, "show running-config all | inc \"traps bfd|traps pim\"", worker, false);
            for (String l : snmpTraps) {
                res.append(l);
                res.append("\n");
            }
        }

        if (ned.hasBehaviour("ipv6-snooping-policy")) {
            res.append("! PARSER_EXIT_TO_TOP\n");
            List<String> ipv6Snooping = updateConfig(null, "show running-config all | sec \"ipv6 snooping\"", worker, false);
            for (String l : ipv6Snooping) {
                res.append(l);
                res.append("\n");
            }
        }

        boolean showAll = isNetsim ? false : ned.hasBehaviour("show-interface-all");
        if (showAll) {
            String showInterfaceAllCmd = ned.showInterfaceAllCmd;
            res.append("! PARSER_EXIT_TO_TOP\n");
            if (showInterfaceAllCmd == null) {
                showInterfaceAllCmd = "show running-config interface all | include \"interface|shutdown|switchport\" | exclude Time: | exclude vlan | exclude mode";
            }
            try {
                lastIfaceConfig =
                    updateConfig(lastIfaceConfig,
                                 showInterfaceAllCmd,
                                 worker, useDiff);
                if (ned.hasBehaviour("show-interface-extras")) {
                    lastIfaceFCConfig =
                        updateConfig(lastIfaceFCConfig,
                                     "show running-config interface all | sec Ethernet | include \"interface Ethernet|flowcontrol|mtu\"",
                                     worker, useDiff);
                } else {
                    lastIfaceFCConfig = null;
                }
            } catch (NedException e) {
                ned.logError(worker, "NexusCLIDevice::getConfig: " + e.getMessage() + ", disabling '" + "show-interface-all" + "'");
                ned.disableBehaviour("show-interface-all");
                return getConfig(worker);
            }
            for (String l : lastIfaceConfig) {
                res.append(l);
                res.append("\n");
            }
            if (lastIfaceFCConfig != null) {
                /* Maybe not needed, thought I saw content from other interface in top */
                boolean foundIface = false;
                for (String l : lastIfaceFCConfig) {
                    if (l.startsWith("interface Ethernet")) {
                        foundIface = true;
                    }
                    if (foundIface) {
                        res.append(l);
                        res.append("\n");
                    }
                }
            }
        }

        ned.logDebug(worker, "CLI GETCONFIG OK");

        return res.toString();
    }

    @Override
    public String
    command(NedWorker worker, String cmd, boolean config) throws Exception {
        return command(worker, cmd,
                       config ? CommandType.CONFIG_CMD : CommandType.EXEC_CMD, null);
    }

    @Override
    public String
    command(NedWorker worker, String cmd, CommandType cmdType, String[] answers) throws Exception {
        ned.logDebug(worker, "CLI COMMAND ==>");

        if (cmdType != CommandType.EXEC_CMD && !enterConfig(worker, NedCmd.CMD)) {
            throw new NedException("error entering config mode");
        }

        // Send command and wait for echo
        String[] patterns = new String[] {
            PROMPT,
            "^.*?[:?] *(?:\\([^\\)]+\\) *)?(?:\\[[^\\]]+\\][ \\?]*)?$",
        };
        boolean cmdHelp = false;
        if (cmd.trim().endsWith("?")) {
            cmdHelp = true;
            ned.session.print(cmd);
            patterns[0] = "\u0008*\u001b\\[J";
        } else {
            ned.session.print(cmd + "\n");
        }
        ned.session.expect(new String[] { Pattern.quote(cmd) }, worker);

        // Wait for device reply
        String res = "";
        if (cmd.contains("show ") && !cmdHelp) {
            /*
             * dirty workaround for 'show version' command. Its output contains
             * patterns matching the regex from below used for commands with
             * interactive mode. Usually show commands don't have this kind of
             * interactive mode
             */
            res = ned.session.expect(PROMPT, worker);

        } else {
            boolean gotPrompt = false;
            int currentAnswer = 0;
            NedExpectResult eres = null;

            boolean hasAutoPrompts = !ned.autoPrompts.isEmpty();
            if (hasAutoPrompts) {
                patterns = Arrays.copyOf(patterns, 2 + ned.autoPrompts.size());
                patterns[patterns.length - 1] = patterns[1]; // move general prompt pattern last
                int dynpromptIdx = 1;
                for (Map<String,String> prompt : ned.autoPrompts) {
                    patterns[dynpromptIdx++] = prompt.get("question");
                    ned.traceVerbose(worker, "  auto-prompts "+stringQuote(prompt.get("question"))
                                     +" => "+stringQuote(prompt.get("answer")));
                }
            }

            boolean reloading = false;
            long lastTimeout = ned.setWriteTimeout(worker);
            do {
                try {
                    lastTimeout = ned.resetWriteTimeout(worker, lastTimeout);
                    ned.traceInfo(worker, "Waiting for command reply (write-timeout "+ned.writeTimeout+")");

                    eres = ned.session.expect(patterns, false, ned.writeTimeout, worker);
                    gotPrompt = (eres.getHit() > 0);

                } catch (IOException|SSHSessionException e) {
                    if (cmd.equals("reload") ||
                        (answers != null && "_NOWAIT_".equals(answers[answers.length-1].trim()))) {
                        String msg = "NOTE: Timeout|EOF during reload|_NOWAIT_ => ending command";
                        ned.logInfo(worker, msg);
                        res += "\n" + msg + "\n";
                        gotPrompt = false;
                        reloading = true;
                    } else {
                        long diff = System.currentTimeMillis() - lastTimeout;
                        String time = Long.toString(diff/1000);
                        String msg = String.format("Timeout waiting for response, after %ss", time);
                        throw new NedException(msg);
                    }
                }

                if (gotPrompt) {
                    ned.logDebug(worker, String.format("Matched auto-prompt : %d", eres.getHit()));

                    // Look up answer
                    String reply = null;
                    if (hasAutoPrompts && (eres.getHit() > 0) && (eres.getHit() < (patterns.length - 1))) {
                        reply = ned.autoPrompts.get(eres.getHit() - 1).get("answer");
                    } else if ((answers != null) && (currentAnswer < answers.length) &&
                               !"_NOWAIT_".equals(answers[currentAnswer].trim())) {
                        reply = answers[currentAnswer++].trim();
                    }

                    // Send answer and newline
                    if (reply != null) {
                        ned.traceInfo(worker, "Sending answer "+stringQuote(reply));
                        ned.session.print(reply);
                    } else {
                        // Collect input when no explicit answer, since it might be "false" prompt
                        res += eres.getText();
                        res += eres.getMatch();
                    }
                    ned.traceVerbose(worker, "Sending newline");
                    ned.session.print("\n");

                    // Check if we are rebooting
                    if ((cmd.equals("reload") && reply != null && reply.startsWith("y")) ||
                        ((answers != null) && (currentAnswer < answers.length) &&
                         "_NOWAIT_".equals(answers[currentAnswer].trim()))) {
                        ned.traceInfo(worker, "reload|_NOWAIT_ - skipping wait for device output");
                        gotPrompt = false;
                        reloading = true;
                    }
                }
            } while (gotPrompt);

            if (reloading) {
                ned.logInfo(worker, "NOTE: Closing session, assuming device is reloading");
                ned.session.close();
                worker.setTimeout(20*1000);
                sleep(worker, 10*1000, true);
            }

            worker.setTimeout(ned.readTimeout);

            if (eres != null) {
                res += eres.getText();
            }
        }

        if (cmdHelp) {
            res = res.replaceAll("\u0008*\u001b\\[J", "");
            ned.session.print("\u0001\u000b");
        }

        if (cmdType == CommandType.CONFIG_CMD) {
            exitConfig();
        }

        ned.logDebug(worker, "CLI COMMAND OK");

        return res;
    }

    private void exitConfig() throws IOException, SSHSessionException {
        ned.session.print("end\n");
        ned.session.expect("\\A\\S*#");
    }

    private boolean enterConfig(NedWorker worker, int cmd)
        throws NedException, IOException, SSHSessionException {
        NedExpectResult res = null;

        ned.session.print("config t\n");
        res = ned.session.expect(new String[]
            {
             "Do you want to kill that session and continue",
             "\\A\\S*\\(config\\)#",
             "\\A\\S*\\(config.*\\)#",
             "Aborted.*\n",
             "Error.*\n",
             "syntax error.*\n",
             "error:.*\n"
            }, worker);

        if (res.getHit() > 2) {
            // Errors
            worker.error(cmd, res.getText());
            return false;
        }

        else if (res.getHit() == 0) {
            ned.session.print("yes\n");
            res = ned.session.expect(new String[]
                {
                 "\\A\\S*\\(config\\)#",
                 "\\A\\S*\\(cfg\\)#",
                 "\\A\\S*\\(config.*\\)#",
                 "\\A\\S*\\(cfg.*\\)#",
                 "Aborted.*\n",
                 "Error.*\n",
                 "syntax error.*\n",
                 "error:.*\n"
                }, worker);

            if (res.getHit() > 3) {
                worker.error(cmd, res.getText());
                return false;
            }
        }

        return true;
    }

    private boolean
    print_line_wait(NedWorker worker, int cmd, String line, int retrying)
        throws NedException, IOException, SSHSessionException,
        CLIApplyException {

        NedExpectResult res = null;
        boolean isAtTop = false;

        // Send command line + newline and wait for prompt
        ned.session.print(line+"\n");
        String echoLine = line.replace("\u0016", ""); // Remove ctrl-v used to enter '?'
        if (waitForEcho) {
            if (line.length() > 120) {
                // Very long lines seems to trigger strange behaviour
                // echo contains " ^H" after ~500 chars
                for (int n = 0; n < echoLine.length(); n++) {
                    String c = echoLine.substring(n, n+1);
                    ned.session.expect(new String[] { Pattern.quote(c) }, worker);
                }
            } else {
                ned.session.expect(new String[] { Pattern.quote(echoLine) }, worker);
            }
        } else if (useEchoDelim) {
            ned.session.print("echo __end_of_ned_cmd__\n");
            ned.session.expect("__end_of_ned_cmd__", worker);
            ned.session.expect("__end_of_ned_cmd__", worker);
        }
        res = ned.session.expect(plw, worker);

        // Check for a blocking confirmation prompt
        if (res.getHit() >= PROMPT_PATTERN_INDEX) {
            // Send: "y" and wait for prompt
            ned.session.print("y\n");
            res = ned.session.expect(plw, worker);
        }

        String reply = res.getText();

        if (res.getHit() == 0)
            isAtTop = true;
        else if (res.getHit() < 3)
            isAtTop = false;
        else if (reply.contains("Exit maintenance profile mode")) {
            // Special case, deleting maintenance profile
            if (!enterConfig(worker, cmd)) {
                throw new CLIApplyException(line, "failed to re-enter config mode",
                                            false, false);
            }
            return true;
        } else {
            throw new CLIApplyException(line, "exited from config mode",
                                        false, false);
        }

        if (isCliRetry(reply)) {
            // wait a while and retry
            if ((ned.deviceRetryCount == 0) || (retrying > ned.deviceRetryCount)) {
                // already tried enough, give up
                throw new CLIApplyException(line, reply, isAtTop, true);
            }
            else {
                if (retrying == 0)
                    worker.setTimeout(ned.deviceRetryCount * ned.deviceRetryDelay);
                sleep(worker, ned.deviceRetryDelay, true); // Sleep deviceRetryDelay ms
                return print_line_wait(worker, cmd, line, retrying+1);
            }
        }

        if (isCliError(worker, reply)) {
            throw new CLIApplyException(line, reply, isAtTop, true);
        }

        this.lastReply = reply;

        return isAtTop;
    }

    private static final Pattern HUNK_PAT =
        Pattern.compile("^([0-9]+)(?:,([0-9]+))?([cda])([0-9]+)(?:,([0-9]+))?$");

    private List<String> applyPatch(NedWorker worker, List<String> oldConfig, List<String> patch)
        throws NedException
    {
        int nextHunk = 0;
        int currentLine = 0;
        List<String> newConfig = new ArrayList<>();

        while (nextHunk < patch.size()) {
            String line = patch.get(nextHunk);
            ned.logDebug(worker, "patch next hunk: '" + line + "'");
            Matcher matcher = HUNK_PAT.matcher(line);
            matcher.matches();
            int srcFrom = Integer.parseInt(matcher.group(1));
            int srcTo = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : srcFrom;
            int tgtFrom = Integer.parseInt(matcher.group(4));
            int tgtTo = matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : tgtFrom;
            String op = matcher.group(3);

            StringBuilder sb = new StringBuilder();
            Formatter formatter = new Formatter(sb);
            try {
                formatter.format("%d,%d %s %d,%d", srcFrom, srcTo, op, tgtFrom, tgtTo);
            } finally {
                formatter.close();
            }
            ned.logDebug(worker, "patch: " + sb.toString() + "\n");

            newConfig.addAll(oldConfig.subList(currentLine, srcFrom - 1));
            currentLine = srcTo;

            if ("a".equals(op)) {
                newConfig.add(oldConfig.get(currentLine - 1));
                int n = (tgtTo - tgtFrom) + 1;
                nextHunk += 1;
                List<String> newLines = patch.subList(nextHunk, nextHunk + n);
                for (String l : newLines) {
                    if (l.length() > 2) {
                        newConfig.add(l.substring(2));
                    } else {
                        newConfig.add("");
                    }
                }
                nextHunk += n;
            } else if ("d".equals(op)) {
                int n = (srcTo - srcFrom);
                nextHunk += n + 2;
            } else if ("c".equals(op)) {
                nextHunk += (srcTo - srcFrom) + 3;
                int n = (tgtTo - tgtFrom) + 1;
                List<String> newLines = patch.subList(nextHunk, nextHunk + n);
                for (String l : newLines) {
                    if (l.length() > 2) {
                        newConfig.add(l.substring(2));
                    } else {
                        newConfig.add("");
                    }
                }
                nextHunk += n;
            } else {
                ned.logError(worker, "error using device diff");
                throw new NedException("error applying diff from device");
            }
        }

        newConfig.addAll(oldConfig.subList(currentLine, oldConfig.size()));

        return newConfig;
    }

    private List<String> updateConfig(List<String> lastConfig, String showCmd, NedWorker worker, boolean useDiff)
        throws Exception
    {
        final String diffSuf = useDiff ? " | diff" : "";

        if (useDiff && (lastConfig != null)) {
            String diff;
            ned.session.print(showCmd + diffSuf + "\n");
            ned.session.expect(Pattern.quote(showCmd) + Pattern.quote(diffSuf), worker);

            diff = ned.session.expect(PROMPT, worker).trim();

            if (diff.indexOf("Invalid command at") >= 0) {
                throw new NedException("error getting diff from device");
            }

            if (diff.length() > 0) {
                ned.logDebug(worker, "diff: '" + diff + "'");
                diff = diff.replaceAll("\r", "");
                List<String> patch = Arrays.asList(diff.split("\n"));
                lastConfig = applyPatch(worker, lastConfig, patch);
            } else {
                ned.logDebug(worker, "no diff, reuse last config");
            }
        } else {
            final String exclCmd = useDiff ? " | exclude Command:" : "";
            ned.session.print(showCmd + exclCmd + "\n");
            ned.session.expect(Pattern.quote(showCmd) + Pattern.quote(exclCmd), worker);

            String res;
            if (showCmd.contains("__end_of_ned_cmd__")) {
                res = ned.session.expect("__end_of_ned_cmd__", worker).trim();
                ned.session.expect(PROMPT, worker).trim();
            } else {
                res = ned.session.expect(PROMPT, worker).trim();
            }

            res = res.replaceAll("\r", "");
            lastConfig = Arrays.asList(res.split("\n"));
            if (useDiff) {
                ned.session.print(showCmd + diffSuf + "\n");
                ned.session.expect(PROMPT, worker);
            }
        }

        return lastConfig;
    }

}

@SuppressWarnings("deprecation")
class CLIApplyException extends Exception {
    public final boolean isAtTop;
    public final boolean inConfigMode;

    public CLIApplyException(String msg,
                             boolean isAtTop, boolean inConfigMode) {
        super(msg);
        this.isAtTop = isAtTop;
        this.inConfigMode = inConfigMode;
    }
    public CLIApplyException(String line, String msg,
                             boolean isAtTop, boolean inConfigMode) {
        super("command: "+line+": "+msg);
        this.isAtTop = isAtTop;
        this.inConfigMode = inConfigMode;
    }
}
