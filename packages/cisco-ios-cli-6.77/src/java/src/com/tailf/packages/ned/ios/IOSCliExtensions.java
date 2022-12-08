package com.tailf.packages.ned.ios;

import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.passwordDequote;
import static com.tailf.packages.ned.nedcom.NedString.calculateMd5Sum;
import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.MaapiUtils;

import com.tailf.conf.ConfBuf;
import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IOSCliExtensions implements NedComCliBase.ExtensionsHandler {

    protected IOSNedCli owner;
    protected Schema schema;
    private String locksOperList;

    //private String command; // for listRemoveBeforeRedeploy()
    private String spaces;
    private Set<String> visited;
    private Set<String> haveItem;

    // ned-settings:
    private boolean autoVrfForwardingRestore;
    private boolean autoIpVrfRdRestore;
    private boolean autoIfSwitchportSpPatch;
    private boolean aaaAccountingModeFormat;
    private boolean autoIpCommunityListRepopulate;

    public IOSCliExtensions(IOSNedCli owner) {
        this.owner = owner;
        this.schema = owner.getCurrentSchema();
        this.locksOperList = owner.operRoot + "/locks{%s}";

        this.autoIpVrfRdRestore = owner.nedSettings.getBoolean("auto/ip-vrf-rd-restore", true);
        this.autoVrfForwardingRestore = owner.nedSettings.getBoolean("auto/vrf-forwarding-restore", true);
        this.autoIfSwitchportSpPatch = owner.nedSettings.getBoolean("auto/if-switchport-sp-patch", false);
        this.aaaAccountingModeFormat = owner.nedSettings.getBoolean("api/aaa-accounting-mode-format", false);
        this.autoIpCommunityListRepopulate = owner.nedSettings.getBoolean("auto/ip-community-list-repopulate", false);

        owner.logInfo(null, "IOSCliExtensions created");
    }

    public void initialize() {
        this.spaces = "";
        this.visited = new HashSet<>();
        this.haveItem = new HashSet<>();
    }


    /**
     * Global output exit handler to act on cached handler events
     * @param
     * @return Modified output data
     */
    public String outputExitHandler(NedWorker worker, String data) {
        return null;
    }


    /*
     **************************************************************************
     * HANDLERS
     **************************************************************************
     */

    /**
     * redeploy-with-change - Remove top-list and redeploy when list modified
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void redeployWithChange(final NedWorker worker, Schema.CallbackMetaData meta,
                                   Schema.ParserContext pctx,
                                   final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine().trim();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (visited.contains("list-remove-before-redeploy+"+path)) {
            traceDev(worker, "   ignored, visited");
            return;
        }

        if (!owner.maapi.exists(fromT, path)) {
            traceDev(worker, "   ignored, created");
            return; // entire list created
        }

        // Check device model for 'ip community-list *'
        if (!autoIpCommunityListRepopulate && cmd.contains("ip community-list ") && !owner.iosmodel.contains("C3550")) {
            traceVerbose(worker, "   ignored, non-C3550 model: "+owner.iosmodel);
            visited.add("list-remove-before-redeploy+"+path);
            return;
        }

        // Only check if deleted for mode-lists to allow delete compression
        final String[] metas = meta.argument.split(" :: ");
        final boolean isMode = metas.length == 1;
        if (isMode && !owner.maapi.exists(toT, path)) {
            traceDev(worker, "   ignored, deleted");
            return; // entire mode-list deleted
        }

        visited.add("list-remove-before-redeploy+"+path);

        // Set base command
        String base = "";
        if (!isMode) {
            base = getMatch(cmd, metas[1]) + " ";
            traceDev(worker, "   regex = '"+metas[1]+"'  base = '"+base+"'");
        }

        // A list change, collect entire list
        List<String> lines = new ArrayList<>();
        lines.add(cmd);
        for (;pctx.peekNextLine() != null;) {
            if (!isMode) {
                String next = pctx.peekNextLine();
                if (!next.startsWith(base) && !next.startsWith("no "+base)) {
                    break;
                }
            }
            String next = pctx.popNextLine();
            lines.add(next);
            if (isMode && owner.isTopExit(next)) {
                break;
            }
        }

        // Check if we should redeploy the list (ignore last entry, does not belong to this list)
        final String trigger = metas[0];
        traceDev(worker, "   trigger = '"+trigger+"'");
        boolean found = false;
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            traceDev(worker, "   <--- "+line);
            if (line.startsWith(trigger)) {
                found = true;
            }
        }
        if (!found) {
            traceDev(worker, "ignored, no line startsWith '"+trigger+"'");
            pctx.injectImmediate(lines);
            return;
        }

        traceOut(worker, "removed and redeployed: "+shortpath(path));

        // (1) Inject list delete
        List<String> inject = new ArrayList<>();
        if (isMode) {
            final String rule = "no " + lines.get(0).trim();
            inject.add(rule);
            // to avoid late delete due to other rule
            traceInfo(worker, "Diff: added temp rule for "+cmd);
            owner.nedDiff.add("", "="+rule+" :: first", "+1");
        } else {
            inject.add(spaces(cmd) + "no " + base);
        }

        // (2) Inject config from CDB
        String config = owner.maapiGetConfig(worker, toT, path, 0);
        if (config != null) {
            for (String line : config.split("\n")) {
                inject.add(line);
            }
        }

        // (3) Redeploy related config (OPTIONAL)
        if (meta.extArguments != null) {
            final String[] extmetas = meta.extArguments.split(" :: ");
            final String path2 = owner.confRoot + extmetas[0];
            if (owner.maapi.exists(toT, path2)) {
                config = owner.maapiGetConfig(worker, toT, path2, 0);
                if (config != null) {
                    for (String line : config.split("\n")) {
                        if (extmetas.length > 2 && !line.startsWith(cmd.replaceAll(extmetas[1], extmetas[2]))) {
                            continue;
                        }
                        inject.add(line);
                    }
                }
            }
        }

        // Inject in parser
        for (String line : inject) {
            traceDev(worker, "   --> "+stringQuote(line));
        }
        pctx.injectImmediate(inject);
    }


    /**
     * if-redeploy - Redeploy interface target value when 'self' is modified
     * @param
     * @throws Exception
     */
    public void ifRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                           Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        final String self = pctx.getNCSCurrentKP(owner.device_id);
        final String ifpath = self.substring(0, self.lastIndexOf("}/")+1);
        traceDev(worker, "   ifpath = "+shortpath(ifpath));

        if (!owner.maapi.exists(toT, ifpath)) {
            return;  // Interface is deleted
        }

        // Do not let switchport content changes redeploy anything
        if (self.endsWith("/switchport")) {
            boolean fromExists = owner.maapi.exists(fromT, self);
            boolean toExists = owner.maapi.exists(toT, self);
            if (fromExists && toExists) {
                // interface * /switchport was not toggled
                return;
            }
        }

        traceDev(worker, "   argument = "+meta.argument);

        // metas[0] = <interface target path>
        // metas[1] = <target cmd>
        // metas[2] = any|create|delete
        // metas[3] = [default value]
        final String[] values = meta.argument.split(" ;; ");
        for (int n = 0; n < values.length; n++) {
            final String[] metas = values[n].split(" :: ");

            // Check if redeploy is disabled for this config
            if (metas[1].startsWith("service-policy ")
                && !owner.nedSettings.getBoolean("auto/if-switchport-sp-redeploy")) {
                continue; // Redeploy of interface service-policy is disabled
            }

            // Check any|create|delete
            if ("create".equals(metas[2]) && pctx.currentDataContext.isDelete()) {
                continue; // Self is deleted
            }
            if ("delete".equals(metas[2]) && !pctx.currentDataContext.isDelete()) {
                continue; // Self is created
            }

            // Get from and to values
            String line = " " + metas[1];

            // type empty (starts with *)
            if (metas[0].startsWith("*")) {
                final String path = metas[0].substring(1);
                boolean from = maapiExists(worker, fromT, ifpath+path);
                if (!from) {
                    continue; // Target is not set
                }
                boolean to = maapiExists(worker, toT, ifpath+path);
                if (!to) {
                    continue; // Target is deleted in this trans
                }

            }

            // type string
            else {
                // Read target from-value and to-value
                final String from = owner.maapiGetLeafString(worker, fromT, ifpath+metas[0]);
                if (from == null) {
                    continue; // Target is not set
                }
                final String to = owner.maapiGetLeafString(worker, toT, ifpath+metas[0]);
                if (to == null) {
                    continue; // Target is deleted in this trans
                }
                if (!from.equals(to)) {
                    continue; // Target is modified in this trans
                }
                if (metas.length > 3 && metas[3].equals(to)) {
                    continue; // Target has default value
                }
                line += (" " + to);
            }

            // Redeploy target value after self is modified
            traceOut(worker, "redeployed '"+line+"' in "+ifpath);
            pctx.injectAfter(line);
        }
    }


    /**
     * if-default - Default interface when created
     * @param
     * @throws Exception
     */
    public void ifDefault(final NedWorker worker, Schema.CallbackMetaData meta,
                          Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        final String ifpath = pctx.getNCSCurrentKP(owner.device_id);
        traceDev(worker, "   ifpath = "+shortpath(ifpath));

        if (owner.maapi.exists(fromT, ifpath)) {
            return;  // Interface not newly created
        }

        // interface * / ip redirects
        if ("true".equals(owner.maapiGetLeafString(worker, toT, ifpath+"/ip/redirects"))) {
            traceOut(worker, "injected interface default 'ip redirects'");
            pctx.injectAfter(" ip redirects");
        }
    }


    /**
     * ned-defaults - Add sent-action for dynamic defaults handling
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void nedDefaults(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        // tokens[0] = "cache-default"
        // tokens[1] = <path>
        // tokens[2] = "default-xxx"
        // tokens[3] = <command>
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String action = "cache-default :: "+path+" :: "+meta.argument+" :: "+cmd;
        traceVerbose(worker, "Adding sent-action: "+action);
        if (!owner.isDry) {
            owner.extSentAction.add(action);
        }
    }


    /**
     * config-lock - this config is locking target config and need to be temporarily removed before edit
     * cli:state "post-match|parent-context-deleted"
     */
    public void configLock(final NedWorker worker, Schema.CallbackMetaData meta,
                           Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isDry) {
            traceDev(worker, "   ignored, dry-run");
            return;
        }

        final String cmd = pctx.getCurrentLine().replace("\r","");
        if (cmd.trim().startsWith("no ")) {
            // Let locks.inject() or show() delete stale lock cache entries
            traceDev(worker, "   ignored for "+stringQuote(cmd));
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String arg = meta.argument;
        final String lockId = path + arg;
        if (visited.contains("config-lock+"+lockId)) {
            traceDev(worker, "   already handled for "+stringQuote(cmd));
            return;
        }
        visited.add("config-lock+"+lockId);

        // Trace debug info
        traceCmd(worker, cmd);
        traceDev(worker, "   argument = "+stringQuote(arg));
        traceDev(worker, "   parent deleted = "
                    +(pctx.getState() == Schema.ParserState.PARENT_CONTEXT_DELETED));

        // Create unique list id and derive oper path
        final String hash = calculateMd5Sum(lockId);
        final String lockOperPath = String.format(this.locksOperList, hash);

        // create|update config lock in oper cache
        try {
            // Create trigger
            final String[] args = arg.split(" :: ");
            String trigger = cmd.replaceFirst(args[0], args[1]);
            String unlock = toEnter(pctx);
            if (meta.extArguments != null) {
                final String[] extmetas = meta.extArguments.split(" :: ");
                final String replacement = getMatch(unlock,extmetas[1]);
                if (replacement != null) {
                    trigger = trigger.replace(extmetas[0],replacement);
                }
            }
            traceDev(worker, "   trigger = "+stringQuote(trigger));

            // Create unlock config
            unlock += (spaces(cmd) + "no "+cmd.trim()+"\n");
            unlock += toExit(pctx);
            traceDev(worker, "   unlock = "+stringQuote(unlock));

            if (!owner.cdbOper.exists(lockOperPath)) {
                traceInfo(worker, "locks: created config lock for "+shortpath(path));
                owner.cdbOper.create(lockOperPath);
            } else {
                traceInfo(worker, "locks: updated config lock for "+shortpath(path));
            }
            owner.cdbOper.setElem(new ConfBuf(path), lockOperPath+"/path");
            owner.cdbOper.setElem(new ConfBuf(trigger), lockOperPath+"/trigger");
            owner.cdbOper.setElem(new ConfBuf(unlock), lockOperPath+"/unlock");

        } catch (Exception e) {
            owner.logError(worker, "locks: extension Exception ERROR :: "+e.getMessage(), e);
        }
    }


    /**
     * ned-diff-minimize-value <path to top> - reorder list in order to minimize value. Uses temp NedDiff rule
     * cli:direction "to-device"
     * cli:state "post-match|call-once"
     */
    public void nedDiffMinimizeValue(final NedWorker worker, Schema.CallbackMetaData meta,
                                     Schema.ParserContext pctx,
                                     final int fromT, final int toT) throws Exception {
        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (!owner.maapi.exists(fromT, path)) {
            // Value not set in from-trans, hence can't be lowered
            return;
        }

        final String top = path + meta.argument;
        if (!maapiExists(worker, fromT, top) || !maapiExists(worker, toT, top)) {
            // Entire list created or deleted
            return;
        }

        if (owner.maapi.exists(toT, path)) {
            long fromVal = owner.maapiGetLeafLong(worker, fromT, path, 0);
            long toVal = owner.maapiGetLeafLong(worker, toT, path, 0);
            traceDev(worker, "  from-val = "+fromVal+"   to-val = "+toVal);
            if (fromVal <= toVal) {
                // Value raised, ignore
                return;
            }
        }

        // Value lowered|deleted, create temporary NedDiff rule
        String fromLine = owner.maapiGetConfig(worker, fromT, path, 0);
        traceDev(worker, "  from-line = "+stringQuote(fromLine));
        final String[] paths = fromLine.split("\n");
        final int numParents = (paths.length - 1) / 2;
        String mode = "";
        for (int n = 0; n < numParents - 1; n++) {
            mode += (" :: "+paths[n].trim());
        }
        if (mode.isEmpty()) {
            String tmpdep = ">"+paths[0].trim()+" :: first";
            traceInfo(worker, "Diff: added temp rule: "+stringQuote(tmpdep));
            owner.nedDiff.add("", tmpdep, "+1");
        } else {
            String tmpdep = ">"+paths[numParents-1].trim()+" :: first";
            mode = mode.substring(4);
            traceInfo(worker, "Diff: added temp sub-mode rule: "+mode+","+stringQuote(tmpdep));
            owner.nedDiff.add(mode, tmpdep, "+1");
        }
    }


    /**
     * replace-output "<regex>"
     * cli:direction "to-device|call-once"
     * cli:state "post-match"
     */
    public void replaceOutput(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        if (cmd.equals(meta.argument)) {
            traceOut(worker, "replaced: '"+cmd+"' with '"+meta.extArguments+"'");
            pctx.replaceCurrentLine(meta.extArguments);
        }
    }


    /**
     * delete-syntax <arg>
     * Four variants:
     *   <arg> = <regexp> with <extargs> = <replacement>
     *   <arg> = <new delete line>
     *   <arg> = null -> silently strip delete line
     *   <arg> = !    -> comment the delete, e.g. ignore but show
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void deleteSyntax(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {

        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (!cmd.trim().startsWith("no ") || owner.isNetsim()) {
            return;
        }

        if (meta.extArguments != null) {
            cmd = cmd.replaceFirst(meta.argument, meta.extArguments);
        } else if ("!".equals(meta.argument)) {
            cmd = spaces(cmd)+"!"+cmd.trim();
        } else if (meta.argument != null) {
            cmd = meta.argument;
        } else {
            pctx.skipCurrentLine(); // Strip delete line
            return;
        }
        traceOut(worker, "to '"+cmd+"'");
        pctx.replaceCurrentLine(cmd);
    }


    /**
     * delete-with-default - prepend 'default ' when deleting
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void deleteWithDefault(NedWorker worker, Schema.CallbackMetaData meta,
                                         Schema.ParserContext pctx, final int fromT, final int toT)
        throws Exception {

        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (!cmd.trim().startsWith("no ") || owner.isNetsim()) {
            return;
        }

        String path = pctx.getNCSCurrentKP(owner.device_id);
        if (maapiExists(worker, toT, path)) {
            return;
        }

        String transformed = cmd.replaceFirst("no ", "default ");
        traceOut(worker, "delete with default '"+transformed+"'");
        pctx.replaceCurrentLine(transformed);
    }


    /**
     * boolean-delete-with-default - prepend 'default ' when deleting boolean true  leaf
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void booleanDeleteWithDefault(NedWorker worker, Schema.CallbackMetaData meta,
                                         Schema.ParserContext pctx, final int fromT, final int toT)
        throws Exception {

        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (cmd.trim().startsWith("no ") || owner.isNetsim()) {
            return;
        }

        String path = pctx.getNCSCurrentKP(owner.device_id);
        if (maapiExists(worker, toT, path)) {
            return;
        }

        String transformed = spaces(cmd) + "default " + cmd.trim();
        traceOut(worker, "boolean delete with default '"+transformed+"'");
        pctx.replaceCurrentLine(transformed);
    }


    /**
     * string-multi-transform - remove|add outer quotes around string without dequoting
     * @param <regex>, where <STRING> is the string to strip quotes from
     * cli:direction "both"
     * cli:state "post-match"
     */
    public void stringMultiTransform(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String line = pctx.getCurrentLine();

        // TO_DEVICE
        if (pctx.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            Pattern p = Pattern.compile(meta.argument.replace("<STRING>", "(\\\"(.+)\\\")"));
            Matcher m = p.matcher(line);
            if (m.find()) {
                final String newline = line.replace(m.group(1), m.group(2));
                traceOut(worker, "'"+line+"' to '"+newline+"'");
                pctx.replaceCurrentLine(newline);
            }
        }

        // FROM_DEVICE
        else {
            Pattern p = Pattern.compile(meta.argument.replace("<STRING>", "(.+)"));
            Matcher m = p.matcher(line);
            if (m.find()) {
                String text = m.group(1);
                if (text.contains("|") && !text.startsWith("\"")) {
                    final String newline = line.replace(m.group(1), "\"" + text + "\"");
                    traceIn(worker, "'"+line+"' to '"+newline+"'");
                    pctx.replaceCurrentLine(newline);
                }
            }
        }
    }


    /**
     * string-quote-input
     * @param <regex>, where <STRING> is the string to quote
     * cli:direction "from-device"
     * cli:state "pre-match|call-once"
     */
    public void stringQuoteInput(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }

        String line = pctx.getCurrentLine();
        Pattern p = Pattern.compile(meta.argument.replace("<STRING>", "(.+)"));
        Matcher m = p.matcher(line);
        if (m.find()) {
            String text = m.group(1);
            if (text.contains(" ")) {
                traceIn(worker, "quoted '"+text+"' in "+line);
                pctx.injectImmediate(line.replace(text, "\""+text+"\""));
                pctx.skipCurrentLine();
            }
        }
    }


    /**
     * string-remove-quotes
     * @param <line> with <STRING> from which quotes should be removed
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void stringRemoveQuotes(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        final String regexp = meta.argument.replace("<STRING>", "\\\"(.+)\\\"");
        final String replacement = meta.argument.replace("<STRING>", "$1");
        final String output = cmd.replaceFirst(regexp, replacement);
        if (!cmd.equals(output)) {
            traceOut(worker, "'"+cmd+"' to '"+output+"'");
            pctx.replaceCurrentLine(output);
        }
    }


    /**
     * password-dequote-output
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void passwordDequoteOutput(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (cmd.indexOf('"') == -1) {
            return;
        }

        // Extract password
        String match = getMatch(cmd, meta.argument);
        if (match == null) {
            return;
        }
        if (match.indexOf('"') == -1) {
            return;
        }

        // Password need to be dequoted before sent to device
        final String output = cmd.replace(match, passwordDequote(match));
        traceOut(worker, "'"+cmd+"' to '"+output+"'");
        pctx.replaceCurrentLine(output);
    }


    /**
     * regex-string
     * Handle escaping and de-escaping of strings containing quotes
     * cli:direction "both";
     * cli:state "post-match|pre-match"
     */
    public void regexString(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {

        if (pctx.isDelete() || owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine();

        //
        // output - dequote string
        //
        if (pctx.parserDirection == Schema.ParserDirection.TO_DEVICE &&
            pctx.getState() == Schema.ParserState.POST_MATCH) {
            traceDev(worker, "   line-out = '"+cmd+"'");
            int i = cmd.indexOf('"');
            if (i >= 0) {
                String value = cmd.substring(i + 1, cmd.length() - 1);
                value = value.replace("\\\"", "\"");
                final String output = cmd.substring(0, i) + value;
                traceOut(worker, "'"+cmd+"' to '"+output+"'");
                pctx.replaceCurrentLine(output);
            }
        }

        //
        // input - quote string
        //
        else if (pctx.parserDirection == Schema.ParserDirection.FROM_DEVICE &&
                 pctx.getState() == Schema.ParserState.PRE_MATCH) {
            traceDev(worker, "   line-in = '"+cmd+"'");
            String value = pctx.currentMatch.restOfLine.toString().trim();
            traceDev(worker, "   value = '"+value+"'");
            final String input = "\"" + value.replace("\"", "\\\"") + "\"";
            traceIn(worker, "'"+value+"' to '"+input+"'");
            pctx.currentMatch.restOfLine.replaceRest(input);
        }
    }


    /**
     * new-aaa-list-syntax
     * Handle new aaa list syntax ned-setting
     * cli:direction "both";
     * cli:state "post-match|pre-match"
     */
    public void newAaaListSyntax(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {

        if (owner.isNetsim() || !owner.newAAAList) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        final String path = pctx.getCurrentKeyPath();

        //
        // output - split group-GRP and cache-GRP
        //
        if (pctx.parserDirection == Schema.ParserDirection.TO_DEVICE
            && pctx.getState() == Schema.ParserState.POST_MATCH) {
            traceDev(worker, "   line-out = '"+cmd+"'");
            final String output = cmd.replace(" group-", " group ").replace(" cache-", " cache ");
            if (!output.equals(cmd)) {
                traceOut(worker, "'"+cmd+"' to '"+output+"'");
                pctx.replaceCurrentLine(output);
                pctx.muteCallback(path, meta);
            }
        }

        //
        // input - join 'group GRP' and 'cache GRP'
        //
        else if (pctx.parserDirection == Schema.ParserDirection.FROM_DEVICE &&
                 pctx.getState() == Schema.ParserState.PRE_MATCH) {
            traceDev(worker, "   line-in = '"+cmd+"'");
            String value = " " + pctx.currentMatch.restOfLine.toString().trim();
            traceDev(worker, "   value = "+stringQuote(value));
            final String input = value.replace(" group ", " group-").replace(" cache ", " cache-");
            if (!input.equals(value)) {
                traceIn(worker, "'"+value+"' to '"+input+"'");
                pctx.currentMatch.restOfLine.replaceRest(input.trim());
                pctx.muteCallback(path, meta);
            }
        }
    }


    /**
     * if-addr-move - pre-inject delete of interface address if added in the same transaction
     * cli:direction "to-device"
     * cli:state "post-match|parent-context-deleted"
     */
    public void ifAddrMove(final NedWorker worker, Schema.CallbackMetaData meta,
                           Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {

        if (!owner.nedSettings.getBoolean("auto/if-address-delete-patch")) {
            return;
        }

        // Assemble parsed lines so far
        StringBuilder sb = new StringBuilder();
        for (String l : pctx.parsedLines()) {
            sb.append(l+"\n");
        }
        final String data = "\n" + sb.toString();

        // No interface(?) address is set before this delete
        if (!(data.contains("\n ip address ") || data.contains("\n ipv6 address "))) {
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        traceDev(worker, "   path = "+shortpath(path));
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        // no interface *
        if (cmd.startsWith("no interface ")) {
            // Interface deleted, always inject shutdown
            String inject = cmd.substring(3)+"\n shutdown\n";
            final String addr = owner.maapiGetLeafString(worker, fromT, path+"/ip/address/primary/address");
            if (addr != null && owner.getConfigToBeParsed().contains("\n ip address "+addr+" ")) {
                traceDev(worker, "   addr = "+addr);
                inject += " no ip address\n";
            }
            inject += "exit\n";
            if (!data.contains(inject) && owner.extInjectFirst.indexOf(inject) < 0) {
                traceOut(worker, "auto/if-address-delete-patch pre-injected "+stringQuote(inject));
                owner.extInjectFirst.append(inject);
            }
            return;
        }

        // ip address
        // no ip address
        else if (" ip address".equals(cmd) || " no ip address".equals(cmd)) {
            final String addr = owner.maapiGetLeafString(worker, fromT, path+"/../../address/primary/address");
            traceDev(worker, "   from-addr = "+addr);
            if (addr == null || !data.contains("\n ip address "+addr)) {
                return; // Address not set in same trans
            }
            cmd = " no ip address"; // !ip address
        }

        // no ip address A.B.C.D M.M.M.M
        // no ipv6 address X:X:X:X::X
        else if (cmd.startsWith(" no ip")) {
            if (!pctx.currentDataContext.isDelete()) {
                return; // Address not deleted
            }
            if (!data.contains("\n"+cmd.substring(3))) {
                return; // Address not set "before" in same trans
            }
        }

        // ip address a.b.c.d A.B.C.D M.M.M.M
        else if (cmd.startsWith(" ip address ")) {
            final String fromAddr = owner.maapiGetLeafString(worker, fromT, path);
            if (fromAddr != null) {
                traceDev(worker, "   from-addr = "+fromAddr);
                haveItem.add(fromAddr);
            }
            String toAddr = getMatch(cmd, " ip address (\\S+)");
            final String ifpath = path.substring(0,path.lastIndexOf('}')+1);
            if (toAddr != null && haveItem.contains(toAddr) && maapiExists(worker, fromT, ifpath)) {
                final String inject = toEnter(pctx).trim()+"\n no ip address\nexit\n";
                if (owner.extInjectFirst.indexOf(inject) < 0) {
                    traceOut(worker, "auto/if-address-delete-patch pre-injected "+stringQuote(inject));
                    owner.extInjectFirst.append(inject);
                }
            }
            return;
        }

        // ipv6 address X:X:X:X::X
        else {
            /* Add ned-diff rule to sort out the case when interface is deleted (hiding the address delete)
            if (owner.getAConfigToBeParsed().contains("\n no"+cmd)) {
                String diffrule = "^interface \\S+<LF>"+cmd+" :: after :: ^interface \\S+<LF> no vrf ";
                traceInfo(worker, "Diff: added auto/if-address-delete-patch rule: "+stringQuote(diffrule));
                owner.nedDiff.add("", diffrule, "+1");
            }
            */
            return;
        }

        // Pre-inject the interface address delete
        final String inject = toEnter(pctx).trim()+"\n"+cmd+"\nexit\n";
        if (owner.extInjectFirst.indexOf(inject) < 0) {
            traceOut(worker, "auto/if-address-delete-patch pre-injected "+stringQuote(inject));
            owner.extInjectFirst.append(inject);
            pctx.skipCurrentLine();
        }
    }


    /**
     * if-sp-move - pre-inject delete of interface service-policy if added in same transaction
     * cli:direction "to-device"
     * cli:state "post-match|parent-context-deleted"
     */
    public void ifSPMove(final NedWorker worker, Schema.CallbackMetaData meta,
                         Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        // Get interface service-policy name
        String path = pctx.getNCSCurrentKP(owner.device_id);
        if (cmd.startsWith("no interface ")) {
            path += "/service-policy/"+meta.argument;
        }
        final String name = owner.maapiGetLeafString(worker, fromT, path);
        if (name == null) {
            return;
        }
        traceDev(worker, "  name = "+name);

        // Check if service-policy is added (on another interface) in same trans
        final String servicePolicy = " service-policy "+meta.argument+" "+name;
        if (!owner.outputData.contains("\n"+servicePolicy+"\n")) {
            return;
        }
        traceDev(worker, "  service-policy = "+servicePolicy);

        // Pre-inject the interface service-policy delete
        String inject = "";
        if (cmd.startsWith("no interface ")) {
            inject = cmd.substring(3) + "\n no" + servicePolicy + "\nexit\n";
        } else {
            inject = toEnter(pctx) + " no" + servicePolicy + "\n" + toExit(pctx);
        }
        if (owner.extInjectFirst.indexOf(inject) < 0) {
            traceOut(worker, "pre-injected "+stringQuote(inject));
            if (cmd.trim().startsWith("no service-policy ")) {
                pctx.skipCurrentLine();
            }
            owner.extInjectFirst.append(inject);
        }
    }


    /**
     * track-remove-before-change - remove old track if type is changed
     * cli:direction "to-device"
     * cli:state "post-match|call-once"
     */
    public void trackRemoveBeforeChange(final NedWorker worker, Schema.CallbackMetaData meta,
                                        Schema.ParserContext pctx, final int fromT, final int toT)
        throws Exception {

        final String cmd = pctx.getCurrentLine();
        if (cmd.startsWith("no track ")) {
            return;
        }
        final String toType = getMatch(cmd, "track \\d+ (\\S+)");

        // Get previous track config
        String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!maapiExists(worker, fromT, path)) {
            return;
        }

        final String from = owner.maapiGetConfig(worker, fromT, path, 0);
        final String fromType = getMatch(from, "track \\d+ (\\S+)");
        if (fromType.equals(toType)) {
            return;
        }

        final String inject = "no "+ getMatch(from, "(track \\d+ .+)");
        traceOut(worker, "pre-injected "+stringQuote(inject));
        pctx.injectBefore(inject);
    }


    /**
     * ignore-re-enter-mode
     * cli:direction "to-device"
     * cli:state "post-match|call-once"
     */
    public void ignoreReEnterMode(final NedWorker worker, Schema.CallbackMetaData meta,
                                  Schema.ParserContext pctx, final int fromT, final int toT)
        throws Exception {
        final String cmd = pctx.getCurrentLine();
        if (cmd.trim().startsWith("no ")) {
            return;
        }

        String path = pctx.getNCSCurrentKP(owner.device_id);
        if (maapiExists(worker, fromT, path)) {
            traceOut(worker, "ignored command to re-enter mode '"+cmd+"'");
            pctx.replaceCurrentLine("");
        }
    }


    /**
     * prune-leaf-list-duplicates
     * cli:direction "from-device"
     * cli:state "post-match"
     */
    public void pruneLeafListDuplicates(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        List<Schema.TreeNode> leafListSeq = ((Schema.TreeSequence)pctx.currentDataContext).getSequence();
        final String path = pctx.currentDataContext.getKeyPath();
        Set<String> values = new HashSet<>();
        List<Schema.TreeNode> toBeRemoved = new ArrayList<>();
        for (Schema.TreeNode n : leafListSeq) {
            String value = ((Schema.TreeLeaf)n).getValue();
            if (values.contains(value)) {
                traceOut(worker, "stripped duplicate "+value+" in "+shortpath(path));
                toBeRemoved.add(n);
            } else {
                values.add(value);
            }
        }
        leafListSeq.removeAll(toBeRemoved);
    }


    /**
     * shutdown-before-modify <leaf> - shutdown container|list before modify
     * cli:arguments = delete-to-disable | exit-and-enter
     * cli:direction "to-device"
     * cli:state "post-match|call-once"
     */
    public void shutdownBeforeModify(final NedWorker worker, Schema.CallbackMetaData meta,
                                     Schema.ParserContext pctx,
                                     final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String extArgs = meta.extArguments != null ? meta.extArguments : "";

        if (pctx.getState() != Schema.ParserState.MULTI_LINE) {

            if (cmd.trim().startsWith("no ")) {
                return; // list header deleted
            }

            final String path = pctx.getCurrentKeyPath();
            traceDev(worker, "  path = "+shortpath(path));
            final String ncsPath = ncspath(path);
            if (!owner.maapi.exists(fromT, ncsPath)) {
                return; // List is created in this transaction
            }

            final String target = ncsPath + "/" + meta.argument;
            if (extArgs.contains("boolean-no")) {
                if (!"false".equals(owner.maapiGetLeafString(worker, fromT, target, "true"))
                    || "true".equals(owner.maapiGetLeafString(worker, toT, target, "true"))) {
                    // 'no shutdown' not set (i.e. != false), no need to inject
                    // or to-trans shutdown
                    return;
                }
            } else if (extArgs.contains("delete-to-disable")) {
                if (!owner.maapi.exists(toT, target)) {
                    // activate not set, no local unlock needed
                }
            } else if (owner.maapi.exists(toT, target)) {
                // shutdown set, no local unlock needed
                return;
            }

            // A list change, collect entire list in order to temporarily inject shutdown
            pctx.startMultiLine(meta);
            pctx.muteCallback(path, meta);
            this.spaces = cmd.replace(cmd.trim(), "");
            return;
        }

        // Collect entire list entry before checking it
        if (!(cmd.equals(this.spaces+"!") || cmd.equals(this.spaces+"exit"))) {
            return;
        }

        // End multi-line mode and trace change
        final String path = ncspath(pctx.getMultiLineKeyPath()); // must be BEFORE endMultiLine
        List<String> lines = pctx.endMultiLine();
        for (String line : lines) {
            traceDev(worker, "   --- "+line);
        }

        //
        // Temporarily inject shutdown
        //
        traceOut(worker, "temporarily inject '"+meta.argument+"' in "+shortpath(path));

        // (1) Add list header
        List<String> inject = new ArrayList<>();
        String enter = lines.remove(0);
        inject.add(enter);

        // (2) Add 'shutdown'
        if (extArgs.contains("delete-to-disable")) {
            inject.add(spaces+" no "+meta.argument);
        } else {
            inject.add(spaces+" "+meta.argument);
        }

        // (3) Optionally add exit and re-enter
        if (extArgs.contains("exit-and-enter")) {
            inject.add(spaces+"exit");
            inject.add(enter);
        }

        // (4) Add transaction changes (excluding exit)
        for (int n = 0; n < lines.size() - 1; n++) {
            inject.add(lines.get(n));
        }

        // (5) Add 'no shutdown'
        if (owner.maapi.exists(toT, path)) {
            if (extArgs.contains("delete-to-disable")) {
                inject.add(spaces+" "+meta.argument);
            } else {
                inject.add(spaces+" no "+meta.argument);
            }
        }

        // (6) Add 'exit'
        inject.add(spaces+"exit");

        pctx.injectImmediate(inject);
    }


    /**
     * shutdown-before-delete - inject shutdown container|list before delete
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void shutdownBeforeDelete(final NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx,
                                     final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        traceDev(worker, "  path = "+shortpath(path));
        if (visited.contains("shutdown-before-delete+"+path)) {
            return;
        }
        visited.add("shutdown-before-delete+"+path);

        // List|container not deleted
        if (maapiExists(worker, toT, path)) {
            return;
        }

        // Top delete
        List<String> inject = new ArrayList<>();
        if (cmd.trim().startsWith("no ")) {
            inject.add(cmd.replaceFirst("no ", ""));
            inject.add(spaces(cmd)+" shutdown");
            inject.add(spaces(cmd)+"exit");
            pctx.injectBefore(inject);
        }

        // Change before a later delete
        else {
            inject.add(" shutdown");
            pctx.injectAfter(inject);
        }

        traceOut(worker, "pre-injected shutdown in '"+cmd+"'");
    }


    /**
     * remove-before-populate
     * cli:direction "to-device"
     * cli:state "post-match|call-once"
     */
    public void removeBeforePopulate(final NedWorker worker, Schema.CallbackMetaData meta,
                                     Schema.ParserContext pctx,
                                     final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);

        if (!cmd.trim().startsWith("no ") && maapiExists(worker, fromT, path)) {
            // added contents, delete presence container on device only
            final String[] args = meta.argument.split(" :: ");
            final String from = owner.maapiGetConfig(worker, fromT, path, Integer.parseInt(args[1])).trim();
            if (args[0].equals(from)) {
                traceOut(worker, "injected 'no "+args[0]+"'");
                pctx.injectBefore(spaces(cmd) + "no " + args[0]);
            }
        }
    }


    /**
     * appnav-controller-change
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void appnavControllerChange(final NedWorker worker, Schema.CallbackMetaData meta,
                                       Schema.ParserContext pctx,
                                       final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (cmd.trim().startsWith("no ")) {
            return;
        }

        // Extract appnav-controller-group
        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String group = getMatch(path, "appnav-controller-group\\{(\\S+?)\\}");

        // Read service-insertion service-context list with appnav-controller-group leaf
        final String ctxRoot = owner.confRoot+"service-insertion/service-context";
        ArrayList<String[]> list = owner.maapiGetObjects(worker, toT, ctxRoot, 2);
        for (String[] entry : list) {
            if (entry[1] == null || !group.equals(entry[1])) {
                continue;
            }
            final String enable = ctxRoot+"{"+entry[0]+"}/enable";
            if (!owner.maapi.exists(toT, enable) || !owner.maapi.exists(fromT, enable)) {
                // ignore if enable not set, or enable set in this transaction
                continue;
            }
            final String redeploy = "service-insertion service-context "+entry[0]+"\n enable\n!\n";
            traceOut(worker, "redeployed: "+stringQuote(redeploy));
            owner.extInjectLast.append(redeploy);
        }
    }


    /**
     * space-flat-list-syntax - convert tailf:cli-range-list-syntax leaf-list to values separated by white-spaces
     * cli:direction "to-device"
     * cli:state "post-match|call-once"
     */
    public void spaceFlatListSyntax(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        Pattern p = Pattern.compile(meta.argument);
        Matcher m = p.matcher(cmd);
        if (!m.find() || !(cmd.contains(",") || cmd.contains("-"))) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        final String[] ranges = m.group(1).split(",");
        for (int r = 0; r < ranges.length; r++) {
            Pattern p2 = Pattern.compile("(\\d+)(?:[-](\\d+))?");
            Matcher m2 = p2.matcher(ranges[r]);
            if (!m2.find()) {
                sb.append(" " + ranges[r]);
                continue;
            }
            int start = Integer.parseInt(m2.group(1));
            int end = m2.group(2) != null ? Integer.parseInt(m2.group(2)) : start;
            for (int v = start; v <= end; v++) {
                sb.append(" " + v);
            }
        }

        if (sb.length() > 0) {
            final String replace = cmd.replace(m.group(1), sb.toString().trim());
            if (!replace.equals(cmd)) {
                traceOut(worker, "converting '"+cmd+"' range leaf-list to '"+replace+"'");
                pctx.replaceCurrentLine(replace);
            }
        }
    }


    /**
     * ip-vrf-rd-restore
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void ipVrfRdRestore(final NedWorker worker, Schema.CallbackMetaData meta,
                               Schema.ParserContext pctx,
                               final int fromT, final int toT) throws Exception {
        if (owner.isNetsim() || !this.autoIpVrfRdRestore) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (cmd.trim().startsWith("no ")) {
            return; // Must have rd to have route-target
        }

        // Restore 'ip vrf * / route-target *'
        restoreRouteTarget(worker, pctx, fromT, toT, "export");
        restoreRouteTarget(worker, pctx, fromT, toT, "import");
    }


    /**
     * Utility method for "ip-vrf-rd-restore" extension
     * @param
     * @return
     */
    private void restoreRouteTarget(NedWorker worker, Schema.ParserContext pctx, int fromT, int toT, String which)
        throws Exception {
        final String path = pctx.getNCSCurrentKP(owner.device_id) + "/../route-target/"+which;
        ArrayList<String[]> from = owner.maapiGetObjects(worker, fromT, path, 1);
        if (from.isEmpty()) {
            return;
        }
        ArrayList<String[]> to = owner.maapiGetObjects(worker, toT, path, 1);
        for (String[] entry : from) {
            if (!listContains(to, entry[0])) {
                continue;
            }
            final String redeploy = " route-target "+which+" "+entry[0];
            traceOut(worker, "redeployed: "+stringQuote(redeploy));
            pctx.injectAfter(redeploy);
        }
    }


    /**
     * split-range-from
     * cli:direction "from-device"
     * cli:state "pre-match"
     */
    public void splitRangeFrom(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine().replace("\r", "");
        traceCmd(worker, cmd);

        Pattern p = Pattern.compile(meta.argument);
        Matcher m = p.matcher(cmd);
        if (!m.find() || !m.group(2).contains("-")) {
            return;
        }
        final String prefix = m.group(1);
        String suffix = "";
        if (m.groupCount() == 3 && m.group(3) != null) {
            suffix = m.group(3);
        }

        p = Pattern.compile("(\\d+)[-](\\d+)");
        m = p.matcher(m.group(2));
        if (!m.find()) {
            return;
        }

        final int start = Integer.parseInt(m.group(1));
        final int end = Integer.parseInt(m.group(2));
        List<String> inject = new ArrayList<>();
        for (int n = start; n <= end; n++) {
            inject.add(prefix + n + suffix);
        }
        traceOut(worker, "split '"+cmd+"' in "+(end-start+1));
        pctx.injectImmediate(inject);
        pctx.skipCurrentLine();
    }


    /**
     * cable-sg-channel-split-range
     * cli:direction "from-device"
     * cli:state "pre-match"
     */
    public void cableSgChannelSplitRange(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine().replace("\r", "");
        traceCmd(worker, cmd);

        // (1) check for 'us-channel x y' line
        Pattern p = Pattern.compile("(.+ sg-channel )(\\d+) (\\d+)( .+ us-channel )(\\d+) (\\d+)");
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            // (2) check for peer-node-us line
            p = Pattern.compile("(.+ sg-channel )(\\d+) (\\d+) peer-node-us");
            m = p.matcher(cmd);
            if (!m.find()) {
                return;
            }
        }

        final String prefix = m.group(1);
        final int start = Integer.parseInt(m.group(2));
        final int end = Integer.parseInt(m.group(3));
        final String suffix = m.groupCount() > 4 ? m.group(4) : " peer-node-us";
        int rf = m.groupCount() > 4 ? Integer.parseInt(m.group(5)) : 0;
        List<String> inject = new ArrayList<>();
        for (int n = start; n <= end; n++, rf++) {
            if (m.groupCount() > 4) {
                inject.add(prefix + n + suffix + rf);
            } else {
                inject.add(prefix + n + suffix);
            }
        }
        traceOut(worker, "split '"+cmd+"' in "+(end-start+1));
        pctx.injectImmediate(inject);
        pctx.skipCurrentLine();
    }


    /**
     * level-1-2-expand
     * cli:direction "from-device"
     * cli:state "pre-match"
     */
    public void level12Expand(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine().replace("\r", "");
        traceCmd(worker, cmd);

        if (cmd.endsWith(" level-1") || cmd.endsWith(" level-2")) {
            return;
        }

        List<String> inject = new ArrayList<>();
        inject.add(cmd + " level-1");
        inject.add(cmd + " level-2");

        traceOut(worker, "appended level-1 and level-2 to '"+cmd+"'");
        pctx.injectImmediate(inject);
        pctx.skipCurrentLine();
        pctx.muteCallback(pctx.getCurrentKeyPath(), meta);
    }


    /**
     * device-range-list-last
     * cli:direction "both"
     * cli:state "pre-match|post-match"
     */
    public void deviceRangeListLast(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        if (pctx.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            if (pctx.getState() != Schema.ParserState.POST_MATCH) {
                return;
            }
            final String cmd = pctx.getCurrentLine();
            String val = ((Schema.TreeLeafList)pctx.currentDataContext).getLeafListValue();
            pctx.replaceCurrentLine(cmd.replace(val,val.replace(",", " ")));
        } else if (pctx.getState() == Schema.ParserState.PRE_MATCH) {
            pctx.currentMatch.restOfLine.replace(" ", ",");
        }
    }


    /**
     * max-values-output "<offset> :: <max values>"
     * Split config lines with multiple values into multiple lines with a maximum number of values per line.
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void maxValuesOutput(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        String sp = spaces(cmd);
        String[] tokens = cmd.trim().split(" ");

        String[] args = meta.argument.split(" :: ");
        int offset = Integer.parseInt(args[0]) + (cmd.trim().startsWith("no ") ? 1 : 0);
        int maxValues = Integer.parseInt(args[1]);
        if (tokens.length <= offset + maxValues) {
            return;
        }
        traceOut(worker, "max-values-output split: "+stringQuote(cmd));

        int num = 0;
        List<String> inject = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int n = offset; n < tokens.length; n++) {
            if (num == 0) {
                for (int i = 0; i < offset; i++) {
                    sb.append(" "+tokens[i]);
                }
            }
            sb.append(" "+tokens[n]);
            if (++num == maxValues || sb.length() > 200) {
                inject.add(sp+sb.toString().trim());
                sb = new StringBuilder();
                num = 0;
            }
        }
        if (num > 0) {
            inject.add(sp+sb.toString().trim());
        }
        pctx.injectImmediate(inject);
        pctx.skipCurrentLine();
    }


    /**
     * if-vrf-restore
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void ifVrfRestore(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isNetsim() || !this.autoVrfForwardingRestore) {
            return;
        }

        final String cmd = pctx.getCurrentLine();

        if (pctx.getState() != Schema.ParserState.MULTI_LINE) {
            final String path = pctx.getNCSCurrentKP(owner.device_id);
            final String ifpath = path.substring(0,path.lastIndexOf('}')+1);
            if (visited.contains("if-vrf-restore+"+path)) {
                return;
            }
            if (!owner.maapi.exists(fromT, ifpath)) {
                return; // interface created
            }
            if (!owner.maapi.exists(toT, ifpath)) {
                return; // interface deleted
            }
            traceCmd(worker, cmd);
            pctx.startMultiLine(meta);
            visited.add("if-vrf-restore+"+path);
            return;
        }

        // Collect entire list entry before checking it
        if (!owner.isTopExit(cmd)) {
            return;
        }

        // End multi-line mode
        final String path = ncspath(pctx.getMultiLineKeyPath());
        final String ifpath = path.substring(0,path.lastIndexOf('}')+1);
        traceVerbose(worker, "  ifpath = "+shortpath(ifpath));
        final boolean v4only = ifpath.contains("ip-vrf/ip");
        List<String> lines = pctx.endMultiLine();

        // Trim all (subsequent) address changes in this transaction
        List<String> trimmed = new ArrayList<>();
        for (String line : lines) {
            traceDev(worker, "   --- "+line);
            if (line.matches("^ (?:no )?ip address .*$")) {
                continue;
            }
            if (!v4only) {
                if (line.matches("^ ipv6 address .*$")) {
                    continue;
                }
                if (line.matches("^ (no )?ipv6 enable$")) {
                    continue;
                }
            }
            trimmed.add(line);
        }

        // Inject
        List<String> inject = new ArrayList<>();

        // (1) Add the vrf change
        inject.add(trimmed.remove(0));

        // (2) Add back all current interface addresses and (optionally) 'ipv6 enable'
        int num = maapiGetIfAddrs(worker, toT, ifpath, v4only, inject);
        if (num > 0) {
            traceOut(worker, "vrf modified, restored "+num+" item(s)");
        }

        // (3) Add back the rest of the interface lines
        for (String line : trimmed) {
            inject.add(line);
        }
        pctx.injectImmediate(inject);
    }



    /**
     * Utility method for "if-vrf-restore", get addresses on interface using Maapi
     * @param
     * @return
     */
    @SuppressWarnings("deprecation")
    private int maapiGetIfAddrs(NedWorker worker, int th, String ifpath, boolean v4only, List<String> inject)
        throws NedException {

        int added = 0;
        StringBuilder sb = new StringBuilder();
        try {
            //
            // (1) interface * / ip address
            //
            String[] primary = owner.maapiGetObject(worker, th, ifpath+"/ip/address/primary", 2);
            if (primary.length == 2 && primary[0] != null) {
                sb.append(" ip address "+primary[0]+" "+primary[1]+"\n");
                added++;

                // interface * / ip address * secondary
                ArrayList<String[]> list = owner.maapiGetObjects(worker, th, ifpath+"/ip/address/secondary", 3);
                for (String[] addr : list) {
                    sb.append(" ip address "+addr[0]+" "+addr[1]+" secondary\n");
                    added++;
                }
            }

            // interface * / ip address dhcp
            else if (maapiExists(worker, th, ifpath+"/ip/address/dhcp")) {
                sb.append(" ip address dhcp");
                String hostname = owner.maapiGetLeafString(worker, th, ifpath+"/ip/address/dhcp/hostname");
                if (hostname != null) {
                    sb.append(" hostname "+hostname);
                }
                sb.append("\n");
                added++;
            }

            // interface * / ip address negotiated
            else if (maapiExists(worker, th, ifpath+"/ip/address/negotiated")) {
                sb.append(" ip address negotiated");
                if (maapiExists(worker, th, ifpath+"/ip/address/negotiated/previous")) {
                    sb.append(" previous");
                }
                sb.append("\n");
                added++;
            }


            //
            // (2) interface * / ipv6 address
            //
            if (!v4only) {

                // interface * / ipv6 enable
                if (maapiExists(worker, th, ifpath+"/ipv6/enable")) {
                    sb.append(" ipv6 enable\n");
                    added++;
                }

                // interface * / ipv6 address *
                ArrayList<String[]> list = owner.maapiGetObjects(worker, th, ifpath+"/ipv6/address/prefix-list", 4);
                if (!list.isEmpty()) {
                    for (String[] addr : list) {
                        sb.append(" ipv6 address "+addr[0]);
                        if (addr[1] != null) {
                            sb.append(" link-local");
                        }
                        if (addr[2] != null) {
                            sb.append(" anycast");
                        }
                        if (addr[3] != null) {
                            sb.append(" eiu-64");
                        }
                        sb.append("\n");
                        added++;
                    }
                }

                // interface * / ipv6 address autoconfig
                else if (maapiExists(worker, th, ifpath+"/ipv6/address/autoconfig")) {
                    sb.append(" ipv6 address autoconfig\n");
                    added++;
                }

                // interface * / ipv6 address dhcp
                else if (maapiExists(worker, th, ifpath+"/ipv6/address/dhcp")) {
                    if (maapiExists(worker, th, ifpath+"/ipv6/address/dhcp/rapid-commit")) {
                        sb.append(" ipv6 address dhcp rapid-commit\n");
                    } else {
                        sb.append(" ipv6 address dhcp\n");
                    }
                    added++;
                }
            }

        } catch (Exception e) {
            throw new NedException("Internal ERROR in if-vrf-restore: "+e.getMessage(), e);
        }

        // Done
        String[] lines = sb.toString().split("\n");
        for (String line : lines) {
            inject.add(line);
        }
        return added;
    }


    /**
     * if-patch-speed
     * @param
     * @throws Exception
     */
    public void ifPatchSpeed(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return;
        }

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        final String trimmed = cmd.trim();
        final String path = pctx.getNCSCurrentKP(owner.device_id);

        if ("no speed".equals(owner.trimBlanks(trimmed))) {
            traceOut(worker, "injected 'speed auto' before 'no speed'");
            pctx.injectBefore(" speed auto");

        } else if ("speed nonegotiate".equals(owner.trimBlanks(trimmed))
                   && maapiExists(worker, fromT, path)) {
            traceOut(worker, "injected 'speed auto' before 'speed nonegotiate'");
            pctx.injectBefore(" speed auto");

        } else if ("speed auto".equals(owner.trimBlanks(trimmed))) {
            String fromD = owner.maapiGetLeafString(worker, fromT, path+"/../duplex");
            if (fromD != null && "full".equals(fromD)) {
                String toD = owner.maapiGetLeafString(worker, toT, path+"/../duplex");
                if (!fromD.equals(toD)) {
                    traceOut(worker, "injected 'no speed' before 'speed auto'");
                    pctx.injectBefore(" no speed");
                }
            }
        }
    }


    /**
     * trim-delete-when-empty - strip container|list options when deleting to avoid leave config on device
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void trimDeleteWhenEmpty(final NedWorker worker, Schema.CallbackMetaData meta,
                                    Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine();
        if (!cmd.trim().startsWith("no ")) {
            return;
        }
        traceCmd(worker, cmd);
        traceDev(worker, "   arg = '"+meta.argument+"'");

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (maapiExists(worker, toT, path)) {
            return;
        }

        Pattern p = Pattern.compile(meta.argument);
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            return;
        }

        final String transformed = cmd.substring(0, m.end(1));
        if (cmd.equals(transformed)) {
            return;
        }

        traceOut(worker, "trimmed delete '"+transformed+"'");
        pctx.replaceCurrentLine(transformed);
    }


    /**
     * trim-when-list - strip create line if parent list deleted
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void trimWhenList(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (cmd.trim().startsWith("no ")) {
            if (!meta.argument.contains("delete")) {
                return;
            }
        } else if (!meta.argument.contains("create")) {
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        traceDev(worker, "   path = "+shortpath(path));
        final String parent = path.substring(0, path.lastIndexOf('}')+1);
        traceDev(worker, "   parent = "+parent);
        if (maapiExists(worker, toT, parent)) {
            return; // parent list exist, don't trim delete
        }

        traceOut(worker, "parent list deleted, stripped '"+cmd+"'");
        pctx.skipCurrentLine();
    }


    /**
     * trim-empty-create
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void trimEmptyCreate(final NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {

        final String cmd = pctx.getCurrentLine().trim();
        final String trimmed = cmd.trim();
        traceCmd(worker, cmd);

        if (trimmed.startsWith("no ")) {
            return;
        }

        if (cmd.matches("^"+meta.argument+"$")
            || (meta.extArguments != null && trimmed.matches("^"+meta.argument+"$"))) {
            traceOut(worker, "trim-empty-create '"+cmd+"'");
            pctx.skipCurrentLine();
        }
    }



    /**
     * display-separated - inject a single entry in non-mode list without sub-leaves
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void displaySeparated(final NedWorker worker, Schema.CallbackMetaData meta,
                                 Schema.ParserContext pctx,
                                 final int fromT, final int toT) throws Exception {

        final String cmd = pctx.getCurrentLine().trim();
        final String trimmed = cmd.trim();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        traceDev(worker, "  path = "+shortpath(path));

        Pattern p = Pattern.compile(meta.argument);
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            traceVerbose(worker, "   regex "+meta.argument+" mismatch");
            return; // Bad regex?
        }

        String line = cmd.replace(cmd, m.group(1));
        if (line.equals(cmd)) {
            traceDev(worker, "   ignored, top-list deleted|created");
            return; // Identical, only top was created|deleted
        }

        // delete
        if (trimmed.startsWith("no ")) {
            if (maapiExists(worker, toT, path)) {
                return; // Partial delete
            }
            final String next = pctx.peekNextLine();
            if (!next.startsWith(line)) {
                traceOut(worker, "display-separated injected '"+line+"'");
                pctx.injectAfter(line);
            }
        }

        // create
        else {
            if (maapiExists(worker, fromT, path)) {
                return; // List already created before
            }
            traceOut(worker, "display-separated injected '"+line+"'");
            pctx.injectBefore(line);
            pctx.muteCallback(pctx.getCurrentKeyPath(), meta);
        }
    }


    /**
     * cable-rpd-diff-add - value 35-44 must be set after core-interface
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void cableRpdDiffAdd(final NedWorker worker, Schema.CallbackMetaData meta,
                                Schema.ParserContext pctx,
                                final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine().trim();
        traceCmd(worker, cmd);
        if (cmd.startsWith("no ")) {
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String to = owner.maapiGetLeafString(worker, toT, path);
        final double val = Double.parseDouble(to);
        if (val >= 35) {
            List<String> lines = pctx.getEnterContextLines();
            final String mode = lines.get(0);
            final String rule = "="+cmd+" :: last";
            traceInfo(worker, "Diff: added temp '"+mode+"' rule: "+stringQuote(rule));
            owner.nedDiff.add(mode, rule, "+1");
        }
    }


    /**
     * snmp-server-all-traps - used with 'cisco-ios api snmp-server-enable-all-traps' ned-setting
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void snmpServerAllTraps(final NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim() || owner.isDry) {
            return;
        }

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if ("snmp-server enable all-traps".equals(cmd)) {
            // create
            pctx.replaceCurrentLine("snmp-server enable traps");
            owner.snmpAllTraps = 1;
        } else {
            // delete
            pctx.replaceCurrentLine("default snmp-server enable traps");
            owner.snmpAllTraps = -1;
        }
    }


    /*
     * erps-inject-delete - when modifying vlan-ids, pre-inject delete's first
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void erpsInjectDelete(final NedWorker worker, Schema.CallbackMetaData meta,
                                 Schema.ParserContext pctx,
                                 final int fromT, final int toT) throws Exception {

        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (!cmd.trim().startsWith("no ")) {
            traceDev(worker, "   ignored, newly created");
            return; // First create, no need to pre-inject
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!maapiExists(worker, toT, path)) {
            traceDev(worker, "   ignored, deleted all");
            return; // Deleted, no need to pre-inject
        }

        // No service instance change, no need to pre-inject
        final String data = owner.getConfigToBeParsed();
        if (!owner.isNetsim() && !data.contains(" service instance ")) {
            traceDev(worker, "   ignored, no service instance change");
            return;
        }

        // Trim the delete
        pctx.skipCurrentLine();

        // Calculate change
        String from = " "+owner.maapiGetLeafString(worker, fromT, path)+" ";
        String to = " "+owner.maapiGetLeafString(worker, toT, path)+" ";
        StringBuilder both = new StringBuilder();
        String[] fromA = from.split(" ");
        for (int f = 0; f < fromA.length; f++) {
            if (to.contains(" "+fromA[f]+" ")) {
                both.append(" "+fromA[f]);
                from = from.replace(" "+fromA[f], "");
                to = to.replace(" "+fromA[f], "");
            }
        }
        final String bothtrim = both.toString().trim();

        // Add or delete only, trim the delete
        if (from.trim().isEmpty() || to.trim().isEmpty()) {
            traceDev(worker, "   trimmed delete, only adding|deleting");
            return;
        }

        // Add and delete, pre-inject common:
        StringBuilder vlans = new StringBuilder();
        if (owner.isNetsim() || bothtrim.isEmpty()) {
            vlans.append("  no inclusion-list vlan-ids\n");
        }
        if (!bothtrim.isEmpty()) {
            vlans.append("  inclusion-list vlan-ids "+bothtrim.replace(" ", ",")+"\n");
        }

        // Inject exclusion-list delete
        if (cmd.contains("exclusion-list")) {
            String vlansbuf = vlans.toString().replace("  ", " ").replace("inclusion", "exclusion");
            Pattern p = Pattern.compile("g8032-list\\{(\\S+)\\}/exclusion-list");
            Matcher m = p.matcher(path);
            if (m.find()) {
                final String header = "ethernet ring g8032 "+m.group(1);
                traceOut(worker, "pre-injected exclusion-list delete in "+header);
                owner.extInjectFirst.append(header+"\n"+vlansbuf+"exit\n");
            }
        }

        // Inject inclusion-list delete
        else {
            Pattern p = Pattern.compile("g8032-list\\{(\\S+)\\}/instance\\{(\\d+)\\}/inclusion-list");
            Matcher m = p.matcher(path);
            if (m.find()) {
                final String header = "ethernet ring g8032 "+m.group(1)+"\n";
                traceOut(worker, "pre-injected inclusion-list delete in "+header.trim());
                final String inst = " instance "+m.group(2)+"\n";
                int index = owner.extInjectFirst.indexOf(header);
                if (index >= 0) {
                    owner.extInjectFirst.insert(index+header.length(), inst+vlans.toString()+" exit\n");
                } else {
                    owner.extInjectFirst.append(header+inst+vlans.toString()+" exit\nexit\n");
                }
            }
        }
    }


    /*
     * if-service-evc-deleted
     * cli:direction "to-device"
     */
    public void ifServiceEvcDeleted(final NedWorker worker, Schema.CallbackMetaData meta,
                                    Schema.ParserContext pctx,
                                    final int fromT, final int toT) throws Exception {
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (visited.contains("if-service-evc-deleted+"+path)) {
            return;
        }
        visited.add("if-service-evc-deleted+"+path);

        if (!maapiExists(worker, toT, path)) {
            traceDev(worker, "   ignored, deleted service instance");
            return;
        }

        final String evcPath = path + "/ethernet-evc-name";
        if (!maapiExists(worker, fromT, evcPath)) {
            traceDev(worker, "   ignored, ethernet-evc-name not set");
            return;
        }
        if (maapiExists(worker, toT, evcPath)) {
            traceDev(worker, "   ignored, ethernet-evc-name not deleted");
            return;
        }

        List<String> inject = new ArrayList<>();
        final String evcName = owner.maapiGetLeafString(worker, fromT, evcPath);
        inject.add(cmd+" "+evcName);
        for (;pctx.peekNextLine() != null;) {
            String next = pctx.popNextLine();
            inject.add(next);
            if (" exit".equals(next)) {
                break;
            }
        }
        inject.add(cmd);
        inject.add(" exit");

        traceOut(worker, "injecting service instance to delete EVC "+evcName);
        pctx.skipCurrentLine();
        pctx.injectImmediate(inject);
    }


    /*
     * privilege-redeploy
     * cli:direction "to-device"
     */
    public void privilegeRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                                  Schema.ParserContext pctx,
                                  final int fromT, final int toT) throws Exception {
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (owner.isNetsim()) {
            return;
        }

        pctx.skipCurrentLine(); // Strip the change line

        String priv = getMatch(cmd, "privilege (\\S+) ");
        if (visited.contains("privilege-redeploy+"+priv)) {
            return;
        }
        visited.add("privilege-redeploy+"+priv);

        final String root = owner.confRoot + "privilege/";
        String path = root + "list";
        if ("exec".equals(priv)) {
            path = root + "exec";
        } else if ("configure".equals(priv)) {
            path = root + "configure";
        }
        traceDev(worker, "   path = "+shortpath(path));

        // Reset (i.e. delete) all old entries
        List<String> lines = new ArrayList<>();
        final String from = owner.maapiGetConfig(worker, fromT, path, 0);
        if (from != null) {
            for (String line : from.split("\n")) {
                line = line.replaceFirst("privilege (\\S+)( all)? level \\d+ (.+)", "privilege $1 all reset $3");
                lines.add(line);
            }
        }

        // Add (back) all existing entries, sorted so longer lines sent first to avoid changes
        List<String> level = new ArrayList<>();
        final String to = owner.maapiGetConfig(worker, toT, path, 0);
        if (to != null) {
            for (String line : to.split("\n")) {
                level.add(line);
            }
            String[] sort = level.toArray(new String[level.size()]);
            Arrays.sort(sort, (a, b)->Integer.compare(b.replaceFirst(" all level ", " level ").length(),
                                                      a.replaceFirst(" all level ", " level ").length()));
            for (int s = 0; s < sort.length; s++) {
                lines.add(sort[s]);
            }
        }

        pctx.injectAfter(lines);
    }


    /*
     * if-switchport-sp-patch - patch for me3600 with switchport clearing service-policy and then bugging out
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void ifSwitchPortSpPatch(final NedWorker worker, Schema.CallbackMetaData meta,
                                    Schema.ParserContext pctx,
                                    final int fromT, final int toT) throws Exception {
        if (owner.isNetsim() || !this.autoIfSwitchportSpPatch) {
            return;
        }

        final String trimmed = pctx.getCurrentLine().trim();
        traceDev(worker, "   trimmed = '"+trimmed+"'");
        if (!"switchport".equals(trimmed) && !"no switchport".equals(trimmed)) {
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        traceDev(worker, "   path = "+shortpath(path));

        // Inject "no service-policy output <name>" since cleared by switchport
        String p = path.replace("/switchport", "/service-policy/output");
        if (maapiExists(worker, toT, p)) {
            String polname = owner.maapiGetLeafString(worker, toT, p);
            traceOut(worker, "injected delete of output service-policy "+polname);
            pctx.injectBefore(" no service-policy output "+polname);
        }

        // Inject "no service-policy input <name>" since cleared by switchport
        p = path.replace("/switchport", "/service-policy/input");
        if (maapiExists(worker, toT, p)) {
            String polname = owner.maapiGetLeafString(worker, toT, p);
            traceOut(worker, "injected delete of input service-policy "+polname);
            pctx.injectBefore(" no service-policy input "+polname);
        }
    }


    /*
     * leaf-list-modify
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void leafListModify(final NedWorker worker, Schema.CallbackMetaData meta,
                                 Schema.ParserContext pctx,
                                 final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return; // netsim
        }

        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!maapiExists(worker, fromT, path)) {
            return; // created
        }
        if (!maapiExists(worker, toT, path)) {
            return; // deleted
        }

        // modified, switch to use delta 'add | remove'
        Pattern p = Pattern.compile(meta.argument);
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            return;
        }

        String inject = "add";
        if (cmd.trim().startsWith("no ")) {
            inject = "remove";
            cmd = cmd.replaceFirst("no ", "");
        }

        traceOut(worker, "modifying '"+cmd+"', injected "+inject+" keyword");
        pctx.replaceCurrentLine(m.group(1) + " " + inject + m.group(2));
    }


    /**
     * leaf-list-modify-replace - used with leaf-list with replace-all
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void leafListModifyReplace(final NedWorker worker, Schema.CallbackMetaData meta,
                                      Schema.ParserContext pctx,
                                      final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return;
        }

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (cmd.contains("second-dot1q")) {
            return; // Not supported yet, must use 'add <inner|outer>'
        }

        // Fast path - creating new entry less than 250 characters
        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (cmd.length() <= 250 && !owner.maapi.exists(fromT, path)) {
            return;
        }

        // Extract prefix
        Pattern p = Pattern.compile("[ ]+(?:no )?([a-z0-9 ]+) ([0-9,-]+)( .+)?");
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            return; // e.g vlans = "any"
        }
        String prefix = spaces(cmd) + m.group(1);
        traceDev(worker, "   prefix = '"+prefix+"'");

        // Fast path - deleting all entries, skip values
        if (!owner.maapi.exists(toT, path)) {
            if (meta.argument != null) {
                pctx.replaceCurrentLine(spaces(cmd)+meta.argument);
            } else {
                pctx.replaceCurrentLine(spaces(cmd)+"no "+m.group(1));
            }
            return;
        }

        // Read entries from CDB
        ArrayList<String[]> fromV = owner.maapiGetObjects(worker, fromT, path, 1);
        ArrayList<String[]> toV = owner.maapiGetObjects(worker, toT, path, 1);

        // Create add|remove
        int injected = doLeafListModifyReplace(prefix, pctx, fromV, toV, "add");
        injected += doLeafListModifyReplace(prefix, pctx, toV, fromV, "remove");
        if (injected > 0) {
            traceOut(worker, "split "+shortpath(path)+" in "+injected);
            pctx.skipCurrentLine();
        }
    }


    /**
     * Utility method for leafListModifyReplace
     * @param
     * @return
     */
    private int doLeafListModifyReplace(String prefix, Schema.ParserContext pctx,
                                        ArrayList<String[]> vlan0, ArrayList<String[]> vlan1, String which)
        throws Exception {

        // Create new list with new entries
        ArrayList<Integer> vlans = new ArrayList<>();
        for (String[] entry : vlan1) {
            if (!listContains(vlan0, entry[0])) {
                vlans.add(Integer.parseInt(entry[0]));
            }
        }

        // No entries added|removed
        if (vlans.isEmpty()) {
            return 0;
        }

        // Compress entries
        ArrayList<String> cvlans = new ArrayList<>();
        for (int v = 0; v < vlans.size(); v++) {
            int low = vlans.get(v);
            int high = low;
            for (int n = v + 1; n < vlans.size(); n++) {
                int next = vlans.get(n);
                if (high + 1 == next) {
                    high = next;
                    v++;
                    continue;
                }
                break;
            }
            if (high > low) {
                cvlans.add(String.format("%d-%d", low, high));
            } else {
                cvlans.add(String.format("%d", low));
            }
        }

        // Inject line(s) with "add|remove" command, max 16 values per line
        List<String> inject = new ArrayList<>();
        for (int b = 0; b < cvlans.size(); b += 16) {
            StringBuilder sb = new StringBuilder(prefix+" ");
            if (!vlan0.isEmpty() || b > 0) {
                sb.append(which+" ");
            }
            for (int i = b; i < b+16 && i < cvlans.size(); i++) {
                if (i > b) {
                    sb.append(",");
                }
                sb.append(cvlans.get(i));
            }
            inject.add(sb.toString());
        }

        pctx.injectAfter(inject);
        return inject.size();
    }


    /*
     * leaf-list-modify-remove - use with leaf-list and tailf:cli-remove-before-change
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void leafListModifyRemove(final NedWorker worker, Schema.CallbackMetaData meta,
                                     Schema.ParserContext pctx,
                                     final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!owner.maapi.exists(fromT, path)) {
            return; // leaf-list created
        }
        if (!owner.maapi.exists(toT, path)) {
            return; // leaf-list deleted
        }

        // modified
        final String delLine = pctx.getCurrentLine().trim();
        traceVerbose(worker, "   del = '"+delLine+"'");

        final String addLine = pctx.peekNextLine();
        traceVerbose(worker, "   add = '"+addLine+"'");

        // [0] = regex, [1] = add keyword, [2] = remote keyword
        final String[] metas = meta.argument.split(" :: ");

        // Create array lists
        Pattern p = Pattern.compile(metas[0]);
        Matcher m0 = p.matcher(delLine);
        Matcher m1 = p.matcher(addLine);
        if (!m0.find() || !m1.find()) {
            return;
        }
        ArrayList<String> delList = owner.rangeListToArray(m0.group(2));
        ArrayList<String> addList = owner.rangeListToArray(m1.group(2));

        // Create delete line(s)
        List<String> inject = new ArrayList<>();
        boolean deletedAll = true;
        for (String delete : delList) {
            if (addList.indexOf(delete) >= 0) {
                deletedAll = false;
                addList.remove(delete);
            } else {
                inject.add(m1.group(1) + " " + metas[2] + " " + delete);
            }
        }

        // Deleted all entries, can't use delta commands
        if (deletedAll) {
            traceVerbose(worker, "replaced all entries, ignoring");
            return;
        }

        // Create add line(s)
        for (String add: addList) {
            inject.add(m1.group(1) + " " + metas[1] + " " + add);
        }

        // Add derived delta commands and trim original
        traceOut(worker, "leaf-list add|remove modify of "+addLine);
        pctx.injectBefore(inject);
        pctx.skipCurrentLine();
        pctx.popNextLine();
    }


    /*
     * aaa-accounting-mode-format
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void aaaAccountingModeFormat(final NedWorker worker, Schema.CallbackMetaData meta,
                                        Schema.ParserContext pctx,
                                        final int fromT, final int toT) throws Exception {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (cmd.trim().startsWith("no ")) {
            return;
        }

        boolean format = this.aaaAccountingModeFormat;
        if (cmd.contains("aaa accounting dot1x ")) {
            int dot1xFormat = nedSettingsGetBooleanInt("api/aaa-accounting-dot1x-mode-format");
            if (dot1xFormat != 0) {
                format = dot1xFormat == 1 ? true : false;
                traceInfo(worker, "   aaa accounting mode format override for dot1x = "+format);
            }
        }
        if (!format) {
            return;
        }

        Pattern p = Pattern.compile("(aaa accounting .+) (none|start-stop|stop-only)"
                                    +"( broadcast)?( group \\S+)?( group \\S+)?");
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            traceInfo(worker, "   ignored, non-matching regex for '"+cmd+"'");
            return;
        }

        traceOut(worker, "aaa accounting mode format for '"+m.group(1)+"'");

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String data = owner.getConfigToBeParsed();
        List<String> inject = new ArrayList<>();
        if (maapiExists(worker, fromT, path) && data.indexOf("no "+m.group(1)+"\n") < 0) {
            inject.add("no "+m.group(1));
        }
        inject.add(m.group(1));
        inject.add(" action-type "+m.group(2));
        if (m.group(3) != null) {
            inject.add(m.group(3));
        }
        if (m.group(4) != null) {
            inject.add(m.group(4));
        }
        if (m.group(5) != null) {
            inject.add(m.group(5));
        }
        inject.add("exit");

        pctx.injectBefore(inject);
        pctx.skipCurrentLine();
    }


    /*
     * list-redeploy - redeploy mode context if leaf modified
     * cli:direction "to-device"
     * cli:state "capture-context"
     */
    public void listRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx,
                             final int fromT, final int toT) throws Exception {

        List<String> lines = pctx.getCapturedLines();
        for (String line : lines) {
            traceDev(worker, "   --> "+stringQuote(line));
        }

        final String path = ncspath(pctx.getMultiLineKeyPath());
        traceDev(worker, "   path = "+shortpath(path));
        if (visited.contains("list-redeploy+"+path)) {
            traceDev(worker, "   ignored, visited");
            return;
        }

        final String[] metas = meta.argument.split(" :: ");
        final String parent = path + metas[0];

        String cmd = lines.get(0);
        if (cmd.trim().startsWith("no ")
            || !maapiExists(worker, toT, parent)
            || !maapiExists(worker, fromT, parent)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, parent deleted|created");
            return;
        }

        final String from = owner.maapiGetLeafString(worker, fromT, path, "");
        final String to = owner.maapiGetLeafString(worker, toT, path, "");
        if (from.equals(to)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, value unmodified");
            return;
        }

        // Redeploy context
        String redeploy = owner.maapiGetConfig(worker, toT, parent, Integer.parseInt(metas[1]));
        traceOut(worker, "redeployed: "+stringQuote(redeploy));
        lines = new ArrayList<>();
        for (String line : redeploy.split("\n")) {
            lines.add(line);
        }
        pctx.injectImmediate(lines);
        visited.add("list-redeploy+"+path);
    }


    /*
     * new-ip-acl-type-change - used when changing type on access-list
     * cli:direction "to-device"
     * cli:state "capture-context"
     */
    public void NewIpAclTypeChange(final NedWorker worker, Schema.CallbackMetaData meta,
                                   Schema.ParserContext pctx,
                                   final int fromT, final int toT) throws Exception {

        List<String> lines = pctx.getCapturedLines();
        String cmd = lines.get(0);
        traceCmd(worker, cmd);
        if (cmd.trim().startsWith("no ")) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, deleted");
            return;
        }

        final String path = ncspath(pctx.getMultiLineKeyPath());
        traceDev(worker, "   path = "+shortpath(path));

        if (visited.contains("new-ip-acl-type-change+"+path)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, injected");
            return;
        }

        String oldType = owner.maapiGetLeafString(worker, fromT, path);
        String newType = owner.maapiGetLeafString(worker, toT, path);
        if (oldType == null || oldType.equals(newType)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, no type change");
            return;
        }

        traceOut(worker, "changing access-list type: "+cmd);

        // Delete the previous list
        List<String> inject = new ArrayList<>();
        if ("standard".equals(oldType)) {
            inject.add("no "+cmd.trim().replace("extended", "standard"));
        } else {
            inject.add("no "+cmd.trim().replace("standard", "extended"));
        }

        // Trim all no commands
        for (String line : lines) {
            if (!line.trim().startsWith("no ")) {
                inject.add(line);
            }
        }
        pctx.injectImmediate(inject);
        visited.add("new-ip-acl-type-change+"+path);
    }


    /*
     * cable-fiber-node-modify
     * cli:direction "to-device"
     * cli:state "capture-context"
     */
    public void cableFiberNodeModify(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx,
                             final int fromT, final int toT) throws Exception {

        List<String> lines = pctx.getCapturedLines();
        for (String line : lines) {
            traceDev(worker, "   --> "+stringQuote(line));
        }

        final String path = ncspath(pctx.getMultiLineKeyPath());
        traceDev(worker, "   path = "+shortpath(path));
        if (visited.contains("cable-fiber-node+"+path)) {
            traceDev(worker, "   ignored, visited");
            return;
        }

        String cmd = lines.get(0);
        if (cmd.trim().startsWith("no ")
            || !maapiExists(worker, toT, path)
            || !maapiExists(worker, fromT, path)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, parent deleted|created");
            return;
        }

        final String target = path + "/service-group/profile";
        if (!maapiExists(worker, fromT, target) || !maapiExists(worker, toT, target)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, service-group profile deleted|created");
            return;
        }

        final String from = owner.maapiGetLeafString(worker, fromT, target, "");
        final String to = owner.maapiGetLeafString(worker, toT, target, "");
        if (!from.equals(to)) {
            pctx.injectImmediate(lines);
            traceDev(worker, "   ignored, service-group profile modified");
            return;
        }

        // Temporarily remove service-group profile
        traceOut(worker, "removed and redeployed: service-group profile in "+shortpath(path));
        List<String> lines2 = new ArrayList<>();
        lines2.add(cmd);
        lines2.add(" no service-group profile " + from);
        for (int i = 1; i < lines.size(); i++) {
            lines2.add(lines.get(i));
        }
        pctx.injectImmediate(lines2);

        // Add back service-group profile last in transaction
        owner.extInjectLast.append(cmd+"\n service-group profile " + to + "\n!\n");

        visited.add("cable-fiber-node+"+path);
    }


    /*
     * ap-dot11-shutdown
     * cli:direction "to-device"
     */
    public void apDot11Shutdown(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx,
                             final int fromT, final int toT) throws Exception {
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        // ap country
        // ap dot11 5ghz
        final String root = owner.confRoot+"ap/dot11/";
        if (!visited.contains("ap-dot11-5ghz-shutdown")
            && (meta.argument == null || meta.argument.contains("5ghz"))
            && !maapiExists(worker, fromT, root + "fiveghz/shutdown")) {
            visited.add("ap-dot11-5ghz-shutdown");
            if (maapiExists(worker, toT, root + "fiveghz/shutdown")) {
                owner.nedDiff.add("", "=ap dot11 5ghz shutdown :: first", "+1");
            } else {
                traceOut(worker, "pre-injected 'ap dot11 5ghz shutdown'");
                pctx.injectBefore("ap dot11 5ghz shutdown");
                traceOut(worker, "post-injected 'no ap dot11 5ghz shutdown'");
                owner.extInjectLast.append("no ap dot11 5ghz shutdown\n");
            }
        }

        // ap country
        // ap dot11 24ghz
        if (!visited.contains("ap-dot11-24ghz-shutdown")
            && (meta.argument == null || meta.argument.contains("24ghz"))
            && !maapiExists(worker, fromT, root + "twentyfourghz/shutdown")) {
            visited.add("ap-dot11-24ghz-shutdown");
            if (maapiExists(worker, toT, root + "twentyfourghz/shutdown")) {
                owner.nedDiff.add("", "=ap dot11 24ghz shutdown :: first", "+1");
            } else {
                traceOut(worker, "pre-injected 'ap dot11 24ghz shutdown'");
                pctx.injectBefore("ap dot11 24ghz shutdown");
                traceOut(worker, "post-injected 'no ap dot11 24ghz shutdown'");
                owner.extInjectLast.append("no ap dot11 24ghz shutdown\n");
            }
        }
    }


    /*
     * bgp-nbr-redeploy-trigger
     * cli:direction "to-device"
     * cli:state "pre-match"
     */
    public void bgpNbrRedeployTrigger(final NedWorker worker, Schema.CallbackMetaData meta,
                                      Schema.ParserContext pctx,
                                      final int fromT, final int toT) throws Exception {
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        if (cmd.trim().startsWith("no ")) {
            return; // peer-group|remote-as deleted
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!maapiExists(worker, fromT, path)) {
            return; // peer-group|remote-as created
        }

        String from = owner.maapiGetLeafString(worker, fromT, path);
        String to = owner.maapiGetLeafString(worker, toT, path);
        if (from.equals(to)) {
            return; // peer-group|remote-as unchanged
        }

        Pattern p = Pattern.compile("(neighbor \\S+ )");
        Matcher m = p.matcher(cmd);
        if (!m.find()) {
            traceInfo(worker, "   BGP: malformed regex for '"+cmd+"'");
            return; // malformed
        }

        traceVerbose(worker, "   BGP: triggered neighbor redeploy for '"+m.group(1)+"'");
        owner.extBgpNbrRedeploy.put(m.group(1), path);
        pctx.muteCallback(pctx.getCurrentKeyPath(), meta);
    }


    /*
     * bgp-nbr-redeploy
     * cli:direction "to-device"
     * cli:state "pre-match"
     *
    public void bgpNbrRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                               Schema.ParserContext pctx,
                               final int fromT, final int toT) throws Exception {
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        for (String nbr : bgpNbrRedeploy) {
            traceVerbose(worker, "   Redeploying BGP "+nbr);
        }
    }
    */


    /*
     **************************************************************************
     * Common utility methods
     **************************************************************************
     */

    /**
     * Return CLI command(s) to enter a mode
     * @param
     * @return
     */
    private String toEnter(Schema.ParserContext pctx) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = pctx.getEnterContextLines();
        for (String line : lines) {
            sb.append(line.replace("\r","")+"\n");
        }
        return sb.toString();
    }


    /**
     * Return CLI command(s) to exit a mode
     * @param
     * @return
     */
    private String toExit(Schema.ParserContext pctx) {
        List<String> lines = pctx.getEnterContextLines();
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        StringBuilder exit = new StringBuilder("exit\n");
        Iterator it = lines.iterator();
        while (it.hasNext()) {
            it.next();
            sb.insert(0, exit.toString());
            exit.insert(0, " ");
        }
        return sb.toString();
    }


    /**
     * Trace methods
     * @param
     */
    private void traceInfo(NedWorker worker, String info) {
        owner.traceInfo(worker, info);
    }
    private void traceVerbose(NedWorker worker, String info) {
        owner.traceVerbose(worker, info);
    }
    private void traceDev(NedWorker worker, String info) {
        owner.traceDev(worker, info);
    }
    private void traceIn(NedWorker worker, String info) {
        traceVerbose(worker, "   transformed <= "+info);
    }
    private void traceOut(NedWorker worker, String info) {
        traceVerbose(worker, "   transformed => "+info);
    }
    private void traceCmd(NedWorker worker, String cmd) {
        traceVerbose(worker, "   cmd = '"+cmd+"'");
    }


    // Common wrappers
    private boolean maapiExists(NedWorker worker, int th, String path) {
        return owner.maapiExists(worker, th, path);
    }

    /**
     * Path methods
     * @param
     * @return
     */
    private String ncspath(String path) {
        path = MaapiUtils.normalizePath(path);
        return schema.createNCSDeviceConfigKP(owner.device_id, path);
    }
    private String shortpath(String path) {
        return owner.shortpath(path);
    }


    /**
     * Return number of spaces " " before a CLI commands
     * @param
     * @return
     */
    private String spaces(String cmd) {
        return cmd.replace(cmd.trim(),"");
    }


    /**
     * Check if name exists in list
     * @param
     * @return
     */
    private boolean listContains(ArrayList<String[]> list, String name) {
        for (String[] entry : list) {
            if (name.equals(entry[0])) {
                return true;
            }
        }
        return false;
    }


    /*
     * Get optional boolean ned-setting, return -1 = false, 0 = unset, 1 = true
     * @param
     * @return
     */
    private int nedSettingsGetBooleanInt(String path) {
        try {
            return owner.nedSettings.getBoolean(path) == true ? 1 : -1;
        } catch (Exception e) {
            // unset ned-settings
            return 0;
        }
    }

}
