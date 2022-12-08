package com.tailf.packages.ned.iosxr;

import static com.tailf.packages.ned.nedcom.NedString.stringQuote;

import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tailf.ned.NedException;
import com.tailf.ned.NedWorker;

import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.MaapiUtils;

public class IosxrCliExtensions implements NedComCliBase.ExtensionsHandler {

    protected IosxrNedCli owner;
    protected Schema schema;
    private Set<String> visited;

    // ned-settings:
    private boolean autoVrfForwardingRestore;
    private boolean apiSpList;

    public IosxrCliExtensions(IosxrNedCli owner) {
        this.owner = owner;
        this.schema = owner.getCurrentSchema();

        try {
            this.autoVrfForwardingRestore = owner.nedSettings.getBoolean("auto/vrf-forwarding-restore");
            this.apiSpList = owner.nedSettings.getBoolean("api/service-policy-list");
        } catch (Exception e) {
            // Ignore
        }
    }

    public void initialize() {
        this.visited = new HashSet<>();
    }


    /**
     * "lock-delete-redeploy <argument>" - Delete and redeploy 'target' lock when 'this' modified
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void lockDeleteRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                           Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (visited.contains("delete-redeploy+"+path+meta.argument)) {
            traceDev(worker, "   ignored, visited");
            return;
        }

        if (!owner.maapi.exists(toT, path) || !owner.maapi.exists(fromT, path)) {
            traceDev(worker, "   ignored, deleted|created");
            return;  // config is deleted or created
        }

        String[] args = meta.argument.split(" :: ");
        final String target = path.replace(args[0], args[1]);
        traceVerbose(worker, "   target = "+target);
        if (!owner.maapi.exists(toT, target) || !owner.maapi.exists(fromT, path)) {
            traceDev(worker, "   ignored, target deleted|created");
            return;  // target is deleted or created
        }

        visited.add("delete-redeploy+"+path+meta.argument);

        String[] extargs = meta.extArguments.split(" :: ");
        final String delete = cmd.replaceAll(extargs[0], extargs[1]);
        traceOut(worker, "pre-injected "+stringQuote(delete));
        owner.extInjectFirst.append(delete+"\n");

        final String redeploy = owner.maapiGetConfig(worker, toT, target, 0);
        traceOut(worker, "delayed redeploy of "+stringQuote(redeploy));
        owner.delayedCommit.append(redeploy);

        // Collect entire entry (all the way to top-exit) before trimming it
        List<String> lines = new ArrayList<>();
        lines.add(cmd);
        for (;pctx.peekNextLine() != null;) {
            String next = pctx.popNextLine();
            lines.add(next);
            if (owner.isTopExit(next)) {
                break;
            }
        }

        // Flush lock changes in this entry (or following it)
        List<String> trimmed = new ArrayList<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n);
            if (line.startsWith(args[2])) {
                for (; n < lines.size(); n++) {
                    String next = lines.get(n);
                    traceDev(worker, "   --- "+next);
                    if (isModeExit(line, next)) {
                        break;
                    }
                }
            } else {
                traceDev(worker, "   +++ "+line);
                trimmed.add(line);
            }
        }

        // Re-inject whole entry for further parsing
        pctx.injectImmediate(trimmed);
    }


    /**
     * "if-delete-redeploy <argument>" - Delete and redeploy interface target value when argument leaf is modified
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void ifDeleteRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                           Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        String line = pctx.getCurrentLine();
        traceVerbose(worker, "   line = "+line);

        if (pctx.getState() == Schema.ParserState.MULTI_LINE) {
            if (owner.isTopExit(line)) {
                pctx.endMultiLine();
            }
            return;
        }

        final String ifpath = pctx.getNCSCurrentKP(owner.device_id);
        traceVerbose(worker, "   ifpath = "+shortpath(ifpath));
        if (isGroupConfig(ifpath)) {
            traceVerbose(worker, "   ignored, group config");
            return;
        }

        if (!owner.maapi.exists(toT, ifpath) || !owner.maapi.exists(fromT, ifpath)) {
            return;  // Interface is deleted or created
        }

        final String path = ifpath + "/" + meta.argument;
        String fromL2 = maapiGetLeafString(worker, fromT, path);
        String toL2 = maapiGetLeafString(worker, toT, path);
        if (fromL2.equals(toL2)) {
            return; // no L2|3 change
        }

        traceDev(worker, "   fromL2 = "+fromL2);
        traceDev(worker, "   toL2 = "+toL2);

        // Delete interface in first commit
        if (!toL2.isEmpty()) {
            line = line.replace(" "+toL2, "");
        }
        pctx.injectBefore("no "+line+" "+fromL2);

        // Redeploy interface in delayed commit
        String redeploy = owner.maapiGetConfig(worker, toT, ifpath, 0);
        StringBuilder sb = new StringBuilder();
        for (String outline : redeploy.split("\n")) {
            if (apiSpList) {
                if (outline.startsWith(" service-policy input-list ")) {
                    outline = outline.replace(" service-policy input-list ", " service-policy input ");
                }
                if (outline.startsWith(" service-policy output-list ")) {
                    outline = outline.replace(" service-policy output-list ", " service-policy output ");
                }
            }
            sb.append(outline+"\n");
        }
        redeploy = sb.toString();
        traceOut(worker, "delete and delayed redeploy: "+stringQuote(redeploy));
        owner.delayedCommit.append(redeploy);

        // Ignore all interface changes in first commit
        pctx.startMultiLine(meta);
    }


    /**
     * "list-modify-redeploy" - If list contents are modified, delete them and redeploy all
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void listModifyRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                                   Schema.ParserContext pctx, final int fromT, final int toT)
        throws Exception {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (pctx.getState() != Schema.ParserState.MULTI_LINE) {
            final String path = pctx.getCurrentKeyPath();
            traceVerbose(worker, "  path = "+path);
            final String ncsPath = ncspath(path);
            if (isGroupConfig(ncsPath)) {
                traceVerbose(worker, "   ignored, group config");
                return;
            }
            if (owner.maapi.exists(fromT, ncsPath) && owner.maapi.exists(toT, ncsPath)) {
                // A list change, collect entire list
                pctx.startMultiLine(meta);
                pctx.muteCallback(path, meta);
            }
            return;
        }

        // Collect entire list entry before checking it
        if (!owner.isTopExit(cmd)) {
            return;
        }

        // End multi-line mode and count additions and deletions
        final String path = ncspath(pctx.getMultiLineKeyPath());
        traceVerbose(worker, "  multi-path = "+path);
        List<String> lines = pctx.endMultiLine();
        int numDel = 0;
        for (String line : lines) {
            traceDev(worker, "   --- "+line);
            if (line.trim().startsWith("no ")) {
                numDel++;
            }
        }
        final int numAdd = lines.size() - numDel - 2;
        traceDev(worker, "   #add = "+numAdd);
        traceDev(worker, "   #del = "+numDel);
        if (numAdd == 0 || numDel == 0) {
            // Only adding or deleting -> ignore redeploy
            pctx.injectImmediate(lines);
            return;
        }

        // Insert delete of old contents
        List<String> inject = deleteContents(owner.maapiGetConfig(worker, fromT, path, 0));
        pctx.injectImmediate(inject);

        // Redeploy list in delayed commit
        String redeploy = owner.maapiGetConfig(worker, toT, path, 0);
        traceOut(worker, "delete contents and delayed redeploy: "+stringQuote(redeploy));
        owner.delayedCommit.append(redeploy);
    }


    /**
     * "mode-delete-redeploy <target>" - Delete and redeploy mode list when 'target' leaf is modified
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void modeDeleteRedeploy(final NedWorker worker, Schema.CallbackMetaData meta,
                                   Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String root = pctx.getNCSCurrentKP(owner.device_id);
        if (visited.contains("mode-delete-redeploy+"+root)) {
            traceDev(worker, "   ignored, visited");
            return;
        }
        visited.add("mode-delete-redeploy+"+root);

        if (!owner.maapiExists(worker, toT, root) || !owner.maapiExists(worker, fromT, root)) {
            traceDev(worker, "   ignored, list deleted|created");
            return;  // list is deleted or created
        }

        // Check if target leaf is modified
        final String path = root + "/" + meta.argument;
        if (owner.maapiExists(worker, toT, path) && owner.maapiExists(worker, fromT, path)) {
            final String from = maapiGetLeafString(worker, fromT, path);
            traceDev(worker, "   target from = "+from);
            final String to = maapiGetLeafString(worker, toT, path);
            traceDev(worker, "   target to = "+to);
            if (from.equals(to)) {
                traceDev(worker, "   ignored, target leaf unmodified");
                return; // no target leaf change
            }
        }

        // Flush list changes in this transaction
        for (;pctx.peekNextLine() != null;) {
            final String line = pctx.popNextLine();
            if (isModeExit(cmd, line)) {
                traceDev(worker, "   flushed: "+stringQuote(line));
                break;
            }
        }

        List<String> inject = new ArrayList<>();
        List<String> modes = pctx.getEnterContextLines();
        final int depth = modes.size();

        // 'this' modified, delete mode list redeploy
        final String from = owner.maapiGetConfig(worker, fromT, root, depth);
        final String oldmode = from.split("\n")[0];
        inject.add(spaces(cmd) + "no "+oldmode.trim());

        final String to = owner.maapiGetConfig(worker, toT, root, depth);
        for (String line : to.split("\n")) {
            inject.add(line);
        }

        traceOut(worker, "deleted and redeployed "+shortpath(root));
        pctx.injectImmediate(inject);
        pctx.skipCurrentLine();
    }


    /**
     * if-strict-name - verify strict interface name
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    @SuppressWarnings("deprecation")
    public void ifStrictName(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx)
        throws Exception {

        if (!owner.nedSettings.getBoolean("api/strict-interface-name")) {
            return;
        }
        if (owner.isDry) {
            traceVerbose(worker, "   ignored, dry-run");
            return;
        }

        Schema.TreeNode leaf = pctx.currentDataContext;
        String ifname = ((Schema.TreeLeaf)leaf).getValue();
        traceVerbose(worker, "   ifname = "+ifname);

        String[] interfaces = {
            "Null",
            "Loopback",
            "Bundle-Ether",
            "Bundle-POS",
            "MgmtEth",
            "FastEthernet",
            "GigabitEthernet",
            "TenGigE",
            "TwentyFiveGigE",
            "FortyGigE",
            "FiftyGigE",
            "HundredGigE",
            "TwoHundredGigE",
            "FourHundredGigE",
            "nve",
            "PW-Ether",
            "Port-channel",
            "POS",
            "PTP",
            "BVI",
            "Vlan",
            "tunnel-ip",
            "tunnel-te",
            "tunnel-tp",
            "tunnel-mte",
            "tunnel-ipsec",
            "ATM",
            "Multilink",
            "SRP",
            "Serial",
            "CEM",
            "GCC0",
            "CSI"
        };
        for (int i = 0; i < interfaces.length; i++) {
            if (ifname.startsWith(interfaces[i])) {
                return;
            }
        }
        throw new NedException("invalid interface name '"+ifname+"' in "+inMode(pctx));
    }


    /**
     * delete-syntax <arg>
     * Three variants:
     *   <arg> = <regexp> with <extargs> = <replacement>
     *   <arg> = <new delete line>
     *   <arg> = null -> strip delete line
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
     * remove-before-change
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void removeBeforeChange(final NedWorker worker, Schema.CallbackMetaData meta,
                                   Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return;
        }

        final String cmd = pctx.getCurrentLine();
        if (cmd.trim().startsWith("no ")) {
            return; // deleted
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!owner.maapi.exists(fromT, path)) {
            return; // created
        }

        String config = owner.maapiGetConfig(worker, fromT, path, 0);
        if (config == null) {
            return;
        }

        // modified
        final String delete = "no " + config;
        traceOut(worker, "remove-before-change: "+delete);
        pctx.injectBefore(delete);
    }


    /**
     * remove-and-commit-before-change
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void removeAndCommitBeforeChange(final NedWorker worker, Schema.CallbackMetaData meta,
                                            Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return;
        }

        final String cmd = pctx.getCurrentLine();
        if (cmd.trim().startsWith("no ")) {
            return; // deleted
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (!owner.maapi.exists(fromT, path)) {
            return; // created
        }

        String config = owner.maapiGetConfig(worker, toT, path, 0);
        if (config == null) {
            return;
        }

        Schema.TreeNode deleteData = schema.newDataTree().createTreeNode(pctx.getCurrentKeyPath());
        deleteData.setDelete(true);

        // modified
        final String delete = pctx.emitCLISingleLine(deleteData);
        traceOut(worker, "remove-and-commit-before-change: "+delete + "\n" + config + "\n***\n");
        pctx.replaceCurrentLine(delete);
        owner.delayedCommit.append(config);
    }


    /**
     * string-add-quotes
     * Add a " before and after specified string, where <STRING> is the string to look at.
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void stringAddQuotes(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim()) {
            return;
        }
        final String cmd = pctx.getCurrentLine();
        String regexp = meta.argument.replace("<STRING>", "(.*)");
        String replacement = meta.argument.replace("<STRING>", "\\\"$1\\\"");
        String output = cmd.replaceFirst(regexp, replacement);
        if (!cmd.equals(output)) {
            traceOut(worker, "'"+cmd+"' to '"+output+"'");
            pctx.replaceCurrentLine(output);
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
     * max-values-output "<offset> :: <max values> [ :: <separator>]
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
        String[] args = meta.argument.split(" :: ");
        String[] tokens;
        if (args.length > 2) {
            tokens = cmd.trim().split("[ "+args[2]+"]");
        } else {
            tokens = cmd.trim().split(" ");
        }
        int offset = Integer.parseInt(args[0]) + (cmd.trim().startsWith("no ") ? 1 : 0);
        int maxValues = Integer.parseInt(args[1]);
        if (tokens.length <= offset + maxValues) {
            return;
        }

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
            if (++num == maxValues) {
                traceOut(worker, "max-values-output: '"+sp+sb.toString().trim()+"'");
                inject.add(sp+sb.toString().trim());
                sb = new StringBuilder();
                num = 0;
            }
        }
        if (num > 0) {
            traceOut(worker, "max-values-output: '"+sp+sb.toString().trim()+"'");
            inject.add(sp+sb.toString().trim());
        }
        pctx.injectAfter(inject);
        pctx.skipCurrentLine();
    }


    /**
     * if-vrf-restore
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void ifVrfRestore(final NedWorker worker, Schema.CallbackMetaData meta,
                             Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isNetsim() || !autoVrfForwardingRestore) {
            return;
        }

        final String cmd = pctx.getCurrentLine();

        if (pctx.getState() != Schema.ParserState.MULTI_LINE) {
            final String path = pctx.getNCSCurrentKP(owner.device_id);
            if (visited.contains("if-vrf-restore+"+path)) {
                return;
            }
            if (!owner.maapi.exists(fromT, path+"/..")) {
                return; // interface created
            }
            if (!owner.maapi.exists(toT, path+"/..")) {
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
        final String ifpath = MaapiUtils.normalizePath(path + "/..");
        traceVerbose(worker, "  ifpath = "+ifpath);
        List<String> lines = pctx.endMultiLine();

        // Trim all (subsequent) address changes in this transaction
        List<String> trimmed = new ArrayList<>();
        for (String line : lines) {
            traceDev(worker, "   --- "+line);
            if (line.matches("^ (no )?ipv(4|6) address .*$")) {
                continue;
            }
            if (line.matches("^ (no )?ipv6 enable$")) {
                continue;
            }
            trimmed.add(line);
        }

        // Inject
        List<String> inject = new ArrayList<>();

        // (1) inject address delete(s)
        if (owner.maapiExists(worker, fromT, ifpath+"/ipv4/address/ip")
            || owner.maapiExists(worker, fromT, ifpath+"/ipv4/ address-secondary-list/address")) {
            inject.add(" no ipv4 address");
        }
        if (owner.maapiExists(worker, fromT, ifpath+"/ipv6/address/prefix-list")) {
            inject.add(" no ipv6 address");
        }
        if (owner.maapiExists(worker, fromT, ifpath+"/ipv6/enable")) {
            inject.add(" no ipv6 enable");
        }

        // (2) Add the vrf change
        inject.add(trimmed.remove(0));

        // (3) Add back all current interface addresses and (optionally) 'ipv6 enable'
        int num = maapiGetIfAddrs(worker, toT, ifpath, inject);
        if (num > 0) {
            traceOut(worker, "vrf modified, restored "+num+" item(s)");
        }

        // (4) Add back the rest of the interface lines
        for (String line : trimmed) {
            inject.add(line);
        }
        pctx.injectImmediate(inject);
    }


    /**
     * Retrieve interfaces address(es) from interface, including options
     * @param
     * @return
     */
    @SuppressWarnings("deprecation")
    private int maapiGetIfAddrs(NedWorker worker, int th, String ifpath, List<String> inject)
        throws NedException {

        int added = 0;
        StringBuilder sb = new StringBuilder();
        try {
            // interface * / ipv4 address
            String address = owner.maapiGetLeafString(worker, th, ifpath+"/ipv4/address/ip");
            if (address != null) {
                String mask = owner.maapiGetLeafString(worker, th, ifpath+"/ipv4/address/mask");
                sb.append(" ipv4 address "+address+" "+mask);
                String tag = owner.maapiGetLeafString(worker, th, ifpath+"/ipv4/address/route-tag");
                if (tag != null) {
                    sb.append(" route-tag "+tag);
                }
                sb.append("\n");
                added++;
            }

            // interface * / ipv4 address * secondary
            ArrayList<String[]> list = owner.maapiGetObjects(worker, th,
                                                             ifpath+"/ipv4/address-secondary-list/address", 4);
            if (!list.isEmpty()) {
                for (String[] addr : list) {
                    sb.append(" ipv4 address "+addr[0]+" "+addr[2]+" secondary");
                    if (addr[3] != null) {
                        sb.append(" route-tag "+addr[3]);
                    }
                    sb.append("\n");
                    added++;
                }
            }

            // interface * / ipv6 enable
            if (owner.maapiExists(worker, th, ifpath+"/ipv6/enable")) {
                sb.append(" ipv6 enable\n");
                added++;
            }

            // interface * / ipv6 address *
            list = owner.maapiGetObjects(worker, th, ifpath+"/ipv6/address/prefix-list", 4);
            if (!list.isEmpty()) {
                for (String[] addr : list) {
                    sb.append(" ipv6 address "+addr[0]);
                    if (addr[1] != null) {
                        sb.append(" eiu-64");
                    }
                    if (addr[2] != null) {
                        sb.append(" link-local");
                    }
                    if (addr[3] != null) {
                        sb.append(" route-tag "+addr[3]);
                    }
                    sb.append("\n");
                    added++;
                }
            }
        } catch (Exception e) {
            throw new NedException("Internal ERROR: "+e.getMessage(), e);
        }

        String[] lines = sb.toString().split("\n");
        for (String line : lines) {
            inject.add(line);
        }

        return added;
    }


    /**
     * snmp-server-all-traps - used with 'api snmp-server-enable-all-traps' ned-setting
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void snmpServerAllTraps(final NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        if (owner.isNetsim() || owner.isDry) {
            return;
        }

        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if ("snmp-server traps all-traps".equals(cmd)) {
            // create
            pctx.replaceCurrentLine("snmp-server traps");
            owner.snmpAllTraps = 1;
        } else {
            // delete
            pctx.replaceCurrentLine("no snmp-server traps");
            owner.snmpAllTraps = -1;
        }
    }


    /**
     * maapi-encrypted
     * cli:direction "to-device"
     * cli:state "post-match";
     */
    public void maapiEncrypted(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        final String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);
        Pattern p = Pattern.compile("( [\"]?\\$[489]\\$[^\\s]*)"); // " $4$<key>" || " $8$<key>" || " $9$<key>"
        Matcher m = p.matcher(cmd);
        if (m.find()) {
            traceOut(worker, "pre-injected maapi-encrypted meta-data tag");
            final String path = pctx.getNCSCurrentKP(owner.device_id);
            pctx.injectBefore(spaces(cmd)+"! meta-data :: "+path+" :: maapi-encrypted");
        }
    }


    /**
     * bgp-vrf-rd-modify
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void bgpVrfRdModify(final NedWorker worker, Schema.CallbackMetaData meta,
                               Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        if (owner.isNetsim()) {
            return;
        }

        final String cmd = pctx.getCurrentLine();

        //
        // Determine if we need to delete and redeploy router bgp * / vrf
        //
        if (pctx.getState() != Schema.ParserState.MULTI_LINE) {

            if (cmd.trim().startsWith("no ")) {
                return; // vrf deleted
            }

            final String path = pctx.getNCSCurrentKP(owner.device_id);
            if (!owner.maapi.exists(fromT, path)) {
                return; // vrf created
            }

            final String rdpath = path + "/rd";
            if (!owner.maapi.exists(fromT, rdpath) || !owner.maapi.exists(toT, rdpath)) {
                return; // rd created or deleted
            }

            final String from = owner.maapiGetLeafString(worker, fromT, rdpath);
            final String to = owner.maapiGetLeafString(worker, toT, rdpath);
            if (from.equals(to)) {
                return; // rd not modified
            }

            // Start multi-line
            pctx.startMultiLine(meta);
            pctx.muteCallback(path, meta);
            return;
        }

        //
        // Collect all router bgp * / vrf changes
        //
        if (!" exit".equals(cmd)) {
            return;
        }

        // End multi-line
        final String path = ncspath(pctx.getMultiLineKeyPath());
        List<String> lines = pctx.endMultiLine();

        // Pre-inject delete of vrf
        String vrf = lines.get(0).trim();
        traceOut(worker, "deleted and redeployed router bgp * / "+stringQuote(vrf));
        pctx.injectAfter(" no "+vrf);

        // Redeploy vrf in delayed commit
        String redeploy = owner.maapiGetConfig(worker, toT, path, 0);
        owner.delayedCommit.append(redeploy);
    }


    public void nsoPatchLeafListDelete(final NedWorker worker, Schema.CallbackMetaData meta,
                                       Schema.ParserContext pctx, final int fromT, final int toT) throws Exception {
        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        if (cmd.trim().startsWith("no ")) {
            return;
        }

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        if (owner.maapiListExists(worker, toT, path)) {
            return;
        }

        traceOut(worker, "stripped invalid line '"+cmd+"' [NSO-PATCH]");
        pctx.skipCurrentLine();
    }


    /**
     * inject-before
     * cli:direction "to-device"
     * cli:state "post-match"
     */
    public void injectBefore(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx)
        throws Exception {

        String cmd = pctx.getCurrentLine();
        traceCmd(worker, cmd);

        final String path = pctx.getNCSCurrentKP(owner.device_id);
        final String id = "inject-before+"+path+meta.argument;
        if (visited.contains(id)) {
            return;
        }
        visited.add(id);

        traceOut(worker, "pre-injected "+meta.argument);
        pctx.injectBefore(meta.argument);
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
            traceIn(worker, "'"+cmd+"' to '"+input+"'");
            pctx.currentMatch.restOfLine.replaceRest(input);
        }
    }

    /**
     * trim-empty-key
     * If key value is empty, trim key from line
     * cli:direction "to-device";
     * cli:state "post-match"
     */
    public void trimEmptyKey(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx) {
        Schema.TreeLeaf leaf = pctx.getLeaf(".");
        if ("".equals(leaf.getValue())) {
            pctx.replaceCurrentLine(pctx.getCurrentLine().replaceFirst(String.format(" %s ?\"\"", leaf.node.token), ""));
        }
    }

    /**
     * trim-remove-before-change
     *
     * To be used in compact container to trim redundant/faulty
     * remove-before-change semantics of NSO. When named child is edited, if all
     * its sibling leaf nodes given as cli:arguments (space separated) are
     * unchanged in to/from and line is a delete, line is trimmed from diff
     *
     * cli:direction "to-device";
     * cli:state "post-match"
     */
    public void trimRemoveBeforeChange(NedWorker worker, Schema.CallbackMetaData meta, Schema.ParserContext pctx,
                                       final int fromT, final int toT) throws Exception {
        if (pctx.isDelete()) {
            String path = pctx.getNCSCurrentKP(owner.device_id);
            boolean existsInFrom = owner.maapi.exists(fromT, path + "/" + meta.argument);
            boolean existsInTo = owner.maapi.exists(toT, path + "/" + meta.argument);
            if (existsInFrom || existsInTo) {
                String fromVal = existsInFrom ? owner.maapiGetLeafString(worker, fromT, path) : null;
                String toVal = existsInTo ? owner.maapiGetLeafString(worker, toT, path) : null;
                if ((fromVal != null) && fromVal.equals(toVal)) {
                    // our value is unchanged, redeployed
                    return;
                }
                String[] siblings = meta.extArguments.split(" ");
                boolean unchanged = true;
                for (String leaf : siblings) {
                    String leafPath = path + "/" + leaf;
                    existsInFrom = owner.maapi.exists(fromT, leafPath);
                    existsInTo = owner.maapi.exists(toT, leafPath);
                    if (existsInFrom && existsInTo) {
                        fromVal = owner.maapiGetLeafString(worker, fromT, leafPath);
                        toVal = owner.maapiGetLeafString(worker, toT, leafPath);
                        unchanged = unchanged && fromVal.equals(toVal);
                    } else if (existsInFrom || existsInTo) {
                        unchanged = false;
                    }
                }
                if (unchanged) {
                    pctx.skipCurrentLine();
                }
            }
        }
    }

    /*
     **************************************************************************
     * Common utility methods
     **************************************************************************
     */


    /**
     * Check if line is mode exit command
     * @param
     * @return
     */
    protected boolean isModeExit(String cmd, String line) {
        String exit1 = spaces(cmd) + "exit";
        String exit2 = spaces(cmd) + "!";
        if (exit1.equals(line)) {
            return true;
        }
        return exit2.equals(line);
    }

    private String inMode(Schema.ParserContext pctx) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = pctx.getEnterContextLines();
        for (String line : lines) {
            sb.append(line.replace("\r","") + "/");
        }
        return sb.toString();
    }

    private List<String> deleteContents(String entry) {
        String[] lines = entry.trim().split("\n");
        List<String> delete = new ArrayList<>();
        delete.add(lines[0]);
        for (int n = 1; n < lines.length; n++) {
            if (owner.isTopExit(lines[n])) {
                delete.add(lines[n]);
                break;
            }
            if (lines[n].startsWith("  ") || lines[n].startsWith(" !") || lines[n].startsWith(" exit")) {
                continue;
            }
            delete.add(" no"+lines[n]);
        }
        return delete;
    }

    private String maapiGetLeafString(NedWorker worker, int th, String path) {
        String val = owner.maapiGetLeafString(worker, th, path);
        if (val == null) {
            return "";
        }
        return val;
    }

    private void traceInfo(NedWorker worker, String info) {
        owner.traceInfo(worker, info);
    }
    private void traceVerbose(NedWorker worker, String info) {
        owner.traceVerbose(worker, info);
    }
    private void traceDev(NedWorker worker, String info) {
        owner.traceDev(worker, info);
    }
    private void traceCmd(NedWorker worker, String cmd) {
        traceVerbose(worker, "   cmd = '"+cmd+"'");
    }
    private void traceIn(NedWorker worker, String info) {
        traceVerbose(worker, "   transformed <= "+info);
    }
    private void traceOut(NedWorker worker, String info) {
        traceVerbose(worker, "   transformed => "+info);
    }

    private String ncspath(String path) {
        return schema.createNCSDeviceConfigKP(owner.device_id, path);
    }
    private String shortpath(String path) {
        return owner.shortpath(path);
    }

    private boolean isGroupConfig(String ncspath) {
        return ncspath.contains("cisco-ios-xr:group{");
    }

    private String spaces(String cmd) {
        return cmd.replace(cmd.trim(),"");
    }
}
