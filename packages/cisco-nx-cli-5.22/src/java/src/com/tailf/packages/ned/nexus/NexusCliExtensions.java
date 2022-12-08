package com.tailf.packages.ned.nexus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tailf.ned.NedException;
import com.tailf.ned.NedWorker;

import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfException;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.DiffIterateOperFlag;
import com.tailf.conf.DiffIterateResultFlag;
import com.tailf.maapi.MaapiCrypto;
import com.tailf.maapi.MaapiException;
import com.tailf.maapi.MaapiDiffIterate;
import com.tailf.maapi.MaapiXPathEvalResult;
import com.tailf.maapi.XPathNodeIterateResultFlag;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuLeaf;
import com.tailf.navu.NavuList;
import com.tailf.navu.NavuListEntry;
import com.tailf.navu.NavuNode;
import com.tailf.ncs.ns.Ncs;

import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.NedCommonLib.NedState;
import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.NavuUtils;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import com.tailf.packages.ned.nedcom.NedProgress;
import static com.tailf.packages.ned.nedcom.NedProgress.reportProgress;

public class NexusCliExtensions implements NedComCliBase.ExtensionsHandler {

    protected NexusNedCli ned;
    protected Schema schema;

    protected Map<String,Schema.ParserContext.InjectBefore> deleteBeforeSetInjects;
    protected Set<String> injectedVsans;
    protected Set<String> vsansToCommit;
    protected Set<String> zonesetsToActivate;
    protected Set<String> hwmodOBFLToBeDisabled;
    protected Set<String> hwmodOBFLToBeEnabled;
    protected Set<String> entryCreated;
    protected boolean changeExtendedToBoth;
    protected Map<String, List<Schema.ParserContext.Replace>> redeployReplaces;
    protected Set<String> redeployTargets;
    protected int currentLineOfDependencies;
    protected List<String> snmpTraps;

    static class RedeployHandler extends Schema.CallbackMetaData {
        static Set<String> addedIn = new HashSet<>();
        public RedeployHandler(Schema.Node node, String state) {
            // TODO: should have util for inject from "template" instead (i.e. extension def)
            super(node, "nx:redeploy-handler", null, state,
                  Schema.ParserDirection.TO_DEVICE, null, null, null,
                  "com.tailf.packages.ned.nexus.NexusCliExtensions.redeployHandler");
        }

        public static void addTo(Schema.Node context, Schema.Node target) {
            addToNode(context, "first-enter-context|last-exit-context");
            addToNode(target, "post-match");
        }

        static void addToNode(Schema.Node node, String state) {
            if (!addedIn.contains(node.xpath)) {
                addedIn.add(node.xpath);
                node.annotations.addCallback(new RedeployHandler(node, state));
            }
        }
    }

    public NexusCliExtensions(NexusNedCli ned) {
        this.ned = ned;
        this.schema = ned.getCurrentSchema();
    }

    public void initialize() {
        this.deleteBeforeSetInjects = new HashMap<>();
        this.injectedVsans = new HashSet<>();
        this.vsansToCommit = new HashSet<>();
        this.zonesetsToActivate = new HashSet<>();
        this.hwmodOBFLToBeDisabled = new HashSet<>();
        this.hwmodOBFLToBeEnabled = new HashSet<>();
        this.entryCreated = new HashSet<>();
        this.changeExtendedToBoth = false;
        this.redeployReplaces = new HashMap<>();
        this.redeployTargets = new HashSet<>();
        this.snmpTraps = new ArrayList<>();
        this.currentLineOfDependencies = -1;
    }

    private NavuContainer getConfigContainerSafe(int th) throws NavuException {
        NavuContainer configContainer = ned.getConfigContainer(th);
        if (configContainer == null) {
            throw new NavuException("Couldn't get device config container from navu");
        }
        return configContainer;
    }

    private int getConfigValue(String confPath, int transId, int unsetVal)
        throws NavuException, IOException, ConfException
    {
        int value = unsetVal;
        if (ned.maapi.exists(transId, confPath)) {
            NavuLeaf node = (NavuLeaf)new NavuContainer(new NavuContext(ned.maapi, transId)).
                container(Ncs.hash).getNavuNode(new ConfPath(confPath));
            value = Integer.parseInt(node.valueAsString());
        } else {
            String tagPath = confPath.replaceAll("\\{[^\\}]+\\}", "");
            Schema.Node node = schema.getNode(tagPath.replaceAll(".*?config/nx:", "/"));
            if ((node instanceof Schema.Leaf) && (((Schema.Leaf)node).defaultVal != null)) {
                value = Integer.parseInt(((Schema.Leaf)node).defaultVal);
            }
        }
        return value;
    }

    // "redeploy-data"
    public void redeployData(final NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, final int fromT, final int toT)
        throws Exception
    {
        final String category = metaData.argument;
        final Set<Schema.Node> nodesInCategory = NexusNedCli.categoryMap.get(category);

        if (!ned.vrfMemberL3Redeploy) {
            // TODO: move out to when in model + fix general when-eval before call-back
            ned.logDebug(worker, String.format("Skip redeploy-data in %s, 'vrf-member-l3-redeploy' disabled", parserContext.currentDataContext.getKeyPath()));
            return;
        }

        Schema.Node context = metaData.node.enclosingContext().self;
        Schema.TreeNode parent = parserContext.currentDataContext.parentContext();
        String parentKP = String.format("%s:%s", schema.module.prefix, parent.getKeyPath().substring(1)); // TODO: move prefix into getKeyPath() (optional)
        ned.logDebug(worker, String.format("redeploy-data '%s', in %s (%s)", category, parserContext.currentDataContext.getKeyPath(), parentKP));
        ConfPath parentCP = new ConfPath(parentKP);
        final NavuNode navuCtx = getConfigContainerSafe(toT).getNavuNode(parentCP);
        final int parentKPLen = NavuUtils.getKeyPath(navuCtx).length();
        final Schema.TreeContainer dataContext = schema.newDataTree(context);

        NavuUtils.traverse(navuCtx, new NavuUtils.NavuTraverser() {
                public void visitLeaf(NavuLeaf navuLeaf) {
                    String navuKP = NavuUtils.getKeyPath(navuLeaf);
                    String relKeyPath = navuKP.substring(parentKPLen+1);
                    try {
                        Schema.Leaf leaf = (Schema.Leaf)NavuUtils.navuToSchema(schema, navuLeaf);
                        if (ned.maapi.exists(toT, navuKP) && ned.maapi.exists(fromT, navuKP) &&
                            nodesInCategory.contains(leaf)) {
                            String value = null;
                            if (!leaf.isTypeEmpty) {
                                value = navuLeaf.valueAsString();
                                if ((value == null) || value.equals(leaf.defaultVal)) {
                                    // Don't redeploy default values (NOTE: navu gives null for default + exists() = true)
                                    return;
                                }
                                String oldValue = ((NavuLeaf)getConfigContainerSafe(fromT).getNavuNode(new ConfPath(navuKP))).valueAsString();
                                if (!value.equals(oldValue)) {
                                    // Only redeploy unchanged values (i.e. no duplicates)
                                    ned.logDebug(worker, String.format("  skip changed value: %s -  %s != %s", relKeyPath, oldValue, value));
                                    return;
                                }
                            }
                            ned.logDebug(worker, String.format("  set value: %s = %s", relKeyPath, value));
                            schema.addData(dataContext, relKeyPath, value);
                        }
                    } catch (Exception e) {
                        ned.logDebug(worker, String.format("  redeploy-data, error getting value for %s", relKeyPath));
                        ned.traceStackTrace(worker, e);
                    }
                }
                public boolean visitListEntry(NavuListEntry entry) {
                    if (entry == navuCtx) {
                        return true;
                    }
                    String entryKP = NavuUtils.getKeyPath(entry);
                    String relKeyPath = entryKP.substring(parentKPLen+1);
                    ned.logDebug(worker, String.format("  check list-entry: %s", relKeyPath));
                    boolean include = nodesInCategory.contains(NavuUtils.navuToSchema(schema, entry));
                    try {
                        if (include && ned.maapi.exists(fromT, entryKP)) {
                            schema.addData(dataContext, relKeyPath);
                        }
                    } catch (Exception e) {
                        ned.logDebug(worker, String.format("  redeploy-data, error checking list-entry %s", relKeyPath));
                        ned.traceStackTrace(worker, e);
                    }
                    return include;
                }
                public boolean visitContainer(NavuNode container) {
                    return nodesInCategory.contains(NavuUtils.navuToSchema(schema, container));
                }
            });

        List<String> linesToInject = parserContext.emitCLI(dataContext);
        if (linesToInject.size() > 0) {
            parserContext.injectImmediate(linesToInject);
            ned.logDebug(worker, String.format("Will inject below to redeploy-data '%s' in %s:", category, NavuUtils.getKeyPath(navuCtx)));
            for (String l : linesToInject) {
                ned.logDebug(worker, String.format("\t'%s'", l));
            }
        } else {
            ned.logDebug(worker, String.format("Nothing to inject for redeploy-data '%s' in %s:", category, NavuUtils.getKeyPath(navuCtx)));
        }
    }

    // "trim-default-on-delete"
    public void trimDefaultOnDelete(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (parserContext.currentDataContext.isDelete() &&
            (parserContext.currentDataContext instanceof Schema.TreeLeaf) &&
            ((Schema.TreeLeaf)parserContext.currentDataContext).isDefault()) {
            String line = parserContext.getCurrentLine();
            String replace = parserContext.emitCLI(parserContext.currentDataContext).get(0).trim().replace("no ", "");
            ned.logDebug(worker, String.format("Do trim-default-on-delete in %s, trim: '%s'", parserContext.currentDataContext.getKeyPath(), replace));
            line = line.replaceAll(replace, "");
            parserContext.replaceCurrentLine(line);
        }
    }

    // "quoted-string"
    public void quotedString(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (parserContext.currentDataContext instanceof Schema.TreeLeaf) {
            String line = parserContext.getCurrentLine();
            String strToQuote = parserContext.currentDataContext.value(null);
            String prefix = parserContext.currentDataContext.node.token;
            if (prefix.length() > 0) {
                prefix = prefix + " ";
            }
            ned.logDebug(worker, String.format("Do quoted-string in %s, string to quote: '%s'", parserContext.currentDataContext.getKeyPath(), strToQuote));
            line = line.replace(String.format("%s%s", prefix, strToQuote), String.format("%s\"%s\"", prefix, strToQuote));
            parserContext.replaceCurrentLine(line);
        }
    }

    // "redeploy-pbr-stats"
    public void redeployPBRStats(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if (parserContext.getCurrentLine().trim().startsWith("no ")) {
            String rmName = parserContext.currentDataContext.value(null);
            ConfPath pbrStatsRMPath = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/config/nx:pbr-stats-route-map/route-map{%s}", ned.device_id, rmName));
            if (ned.maapi.exists(toT, pbrStatsRMPath)) {
                ned.logDebug(worker, String.format("Redeploy %s", pbrStatsRMPath.toString()));
                parserContext.injectImmediate(String.format("route-map %s pbr-statistics", rmName));
            }
        }
    }

    // "macro-expand"
    public void macroExpand(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if ("all-exceptions".equals(metaData.argument) &&
            "all".equals(parserContext.currentMatch.restOfLine.toString().trim())) {
            ned.logDebug(worker, String.format("Expand 'source exception all' in %s", parserContext.currentDataContext.getKeyPath()));
            String[] inject = new String[] {
                "  source exception layer3",
                "  source exception other",
                "  source exception fabricpath"
            };
            parserContext.injectImmediate(Arrays.asList(inject));
        }
    }

    // "diff-list-delete-before-set"
    public void diffListDeleteBeforeSet(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        String line = parserContext.getCurrentLine();
        String listPath = parserContext.currentDataContext.getKeyPath();
        listPath = listPath.substring(0, listPath.lastIndexOf('{'));
        boolean isDelete = line.trim().startsWith("no ");
        Schema.ParserContext.InjectBefore injectBefore = deleteBeforeSetInjects.get(listPath);
        if (!isDelete && (injectBefore == null)) {
            // this is first set line in diff, inject all following deletes before this line
            deleteBeforeSetInjects.put(listPath, parserContext.injectBefore(new ArrayList<String>()));
        } else if ((isDelete) && (injectBefore != null)) {
            injectBefore.addLineLast(line);
            parserContext.skipCurrentLine();
        }
    }

    // "verify-network-address"
    private final static Pattern ipv4AddrPat = Pattern.compile(".*([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)/([0-9]+).*");
    @SuppressWarnings("deprecation")
    public void verifyNetworkAddress(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws NedException
    {
        Schema.TreeNode leaf = parserContext.currentDataContext;
        if (!(leaf instanceof Schema.TreeLeaf)) {
            throw new RuntimeException(String.format("verify-network-address can only be applied on leaf, present here: %s", leaf.node.xpath));
        }
        String value = ((Schema.TreeLeaf)leaf).getValue();
        Matcher m = ipv4AddrPat.matcher(value);
        if (m.find()) {
            int addr32 = Integer.parseInt(m.group(1)) << 24;
            addr32 += Integer.parseInt(m.group(2)) << 16;
            addr32 += Integer.parseInt(m.group(3)) << 8;
            addr32 += Integer.parseInt(m.group(4));
            int mask = ~((~0) << (32 - Integer.parseInt(m.group(5))));
            if ((addr32 & mask) != 0) {
                throw new NedException(String.format("Invalid network address in %s: %s", leaf.getKeyPath(), value));
            }
        }
    }

    // "delete-with"
    public void deleteWith(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        String line = parserContext.getCurrentLine();
        if ((!ned.debugPrepare && ned.isDevice("netsim")) || !line.trim().startsWith("no ")) {
            return;
        }
        Schema.TreeNode node = parserContext.currentDataContext;
        ned.logDebug(worker, "delete-with in " + node.getKeyPath());
        String arg = metaData.argument;
        if (arg.equals("none")) {
            parserContext.skipCurrentLine();
            return;
        }
        if (arg.equals("default")) {
            if (!(node instanceof Schema.TreeLeaf)) {
                throw new RuntimeException(String.format("delete-with 'default' can only be applied on leaf, present here: %s", node.node.xpath));
            }
            String defaultValue = ((Schema.Leaf)node.node).defaultVal;
            node.setDelete(false);
            if (defaultValue == null) {
                throw new RuntimeException(String.format("delete-with 'default' missing default value, present here: %s", node.node.xpath));
            }
            Schema.TreeContainer dataContext = schema.newDataTree();
            Schema.TreeLeaf dataEntry = dataContext.createLeaf(node.getKeyPath());
            dataEntry.setValue(defaultValue);
            arg = parserContext.emitCLISingleLine(dataEntry);

        } else {
            StringBuilder indent = parserContext.currentIndent();
            indent.append(arg);
            arg = indent.toString();
        }
        ned.logDebug(worker, String.format("replace %s with '%s'", line, arg));
        parserContext.replaceCurrentLine(arg);
    }

    // "prune-leaf-list-duplicates"
    public void pruneLeafListDuplicates(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        List<Schema.TreeNode> leafListSeq = ((Schema.TreeSequence)parserContext.currentDataContext).getSequence();
        ned.logDebug(worker, String.format("prune: %s", parserContext.currentDataContext.getKeyPath()));
        Set<String> values = new HashSet<>();
        List<Schema.TreeNode> toBeRemoved = new ArrayList<>();
        for (Schema.TreeNode n : leafListSeq) {
            String value = ((Schema.TreeLeaf)n).getValue();
            if (values.contains(value)) {
                toBeRemoved.add(n);
            } else {
                values.add(value);
            }
        }
        leafListSeq.removeAll(toBeRemoved);
    }

    // "flowcontrol-delete-with-toggle"
    public void flowCtrlDeleteWithToggle(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        String line = parserContext.getCurrentLine();
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        if (line.trim().startsWith("no ")) {
            boolean isOn = !line.trim().endsWith(" off"); // desired or on
            line = line.replaceAll("^(\\s+)no (.*)(?:on|off|desired)", "$1$2" + (isOn ? "off" : "on"));
            parserContext.replaceCurrentLine(line);
        }
    }

    // "explicit-delete-when-empty"
    public void explicitDeleteWhenEmpty(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String line = parserContext.getCurrentLine();
        if (line.trim().startsWith("no ")) {
            String path = parserContext.getNCSCurrentKP(ned.device_id);
            if (!ned.maapi.exists(toT, path) || "full-no".equals(metaData.extArguments)) {
                line = parserContext.emitCLISingleLine(parserContext.currentDataContext, false);
                ned.logDebug(worker, String.format("explicit-delete-when-empty %s : '%s'", path, line));
                if (!line.trim().startsWith("no ")) {
                    // When leaf below is deleted, presence container isn't marked as delete
                    // TODO: can use parserContext.currentDataContext.isDelete()
                    //       but API should be cleaned out/simplified
                    line = line.replaceAll("^(\\s*)(.*)", "$1no $2");
                }
                parserContext.replaceCurrentLine(line);
            }
        }
    }

    // "trim-empty-create"
    public void trimEmptyCreate(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (parserContext.currentMatch.restOfLine.length() == 0) {
            String line = parserContext.getCurrentLine();
            ned.logDebug(worker, String.format("trim-empty-create in %s: %s", parserContext.currentDataContext.getKeyPath(), line));
            // Trim the empty list, can only be generated with juniper-style cli (i.e. not heading cli-delete-when-empty)
            parserContext.skipCurrentLine();
        }
    }

    // "delete-vlan-mappings-on-delete"
    public void deleteVlanMappingsOnDelete(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        String line = parserContext.getCurrentLine();
        if (line.trim().startsWith("no ")) {
            String path = parserContext.getNCSCurrentKP(ned.device_id);
            if (ned.maapi.exists(fromT, path + "/switchport/vlan/mapping/map")) {
                NavuList map = (NavuList)new NavuContainer(new NavuContext(ned.maapi, fromT)).
                    container(Ncs.hash).getNavuNode(new ConfPath(path + "/switchport/vlan/mapping/map"));
                if (map.size() > 0) {
                    line = String.format("%s ; no switchport vlan mapping all ; exit ; %s", line.trim().replace("no ", ""), line);
                    parserContext.replaceCurrentLine(line);
                }
            }
        }
    }

    // "must-be-less-than"
    public void mustBeLessThan(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        String line = parserContext.getCurrentLine();
        String other = metaData.argument;
        int selfVal = Integer.parseInt(((Schema.TreeLeaf)parserContext.currentDataContext).getValue());
        String path = parserContext.getNCSCurrentKP(ned.device_id);
        String otherPath = path.substring(0, path.lastIndexOf('/')+1) + other;
        int otherVal = getConfigValue(otherPath, fromT, selfVal);
        if (selfVal > otherVal) {
            otherVal = getConfigValue(otherPath, toT, selfVal);
            line = String.format("  %s %d ; %s", other, otherVal, line.trim());
            parserContext.replaceCurrentLine(line);
        }
    }

    // "show-on-create-only"
    public void showOnCreateOnly(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        String path = parserContext.getNCSCurrentKP(ned.device_id);
        if (ned.maapi.exists(fromT, path) || entryCreated.contains(path)) {
            String line = parserContext.getCurrentLine();
            line = line.replaceAll("^(\\s+)(\\S.*)$", "$1! $2");
            parserContext.replaceCurrentLine(line);
        }
        entryCreated.add(path);
    }

    // "conditionally-inject-frequency"
    public void conditionallyInjectFrequency(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String path = parserContext.getNCSCurrentKP(ned.device_id);
        int timeout = getConfigValue(path + "/timeout", toT, -1);
        int frequency = getConfigValue(path + "/frequency", fromT, -1);
        if (frequency < 0) {
            return;
        } else {
            frequency *= 1000;
        }
        if (timeout > frequency) {
            frequency = getConfigValue(path + "/frequency", toT, -1);
            String line;
            if (frequency == -1) {
                line = "  no frequency";
            } else {
                line = String.format("  frequency %d", frequency);
            }
            parserContext.injectImmediate(line);
        }
    }

    // "maintenance-mode-cleanup"
    public void maintenanceModeCleanup(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String line = parserContext.getCurrentLine();
        if (line.trim().startsWith("no ")) {
            String path = String.format("/ncs:devices/device{%s}/config/nx:configure/maintenance/profile/normal-mode", ned.device_id);
            if (!ned.maapi.exists(fromT, path) && !ned.maapi.exists(toT, path)) {
                parserContext.injectImmediate("no configure maintenance profile normal-mode");
            }
        }
    }

    private ConfPath secretsOperConfPath(Schema.TreeLeaf leaf, boolean create) throws ConfException, IOException {
        String keyPath = leaf.getKeyPath();
        String operKey = keyPath.replace('{', '_').replace('}', '_').replace(' ', '_');
        ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/secrets:secrets/secret{%s}", ned.device_id, operKey));
        if (create && !ned.cdbOper.exists(cp)) {
            ned.cdbOper.create(cp);
        }
        return cp;
    }

    private void captureCleartext(NedWorker worker, Schema.TreeLeaf leaf, Schema.ParserContext parserContext) throws ConfException, IOException {
        String keyPath = leaf.getKeyPath();
        ConfPath cp = secretsOperConfPath(leaf, true);
        ConfPath cleartextCP = cp.copyAppend("cleartext");
        ConfPath encryptedCP = cp.copyAppend("encrypted");
        String newClear = leaf.getValue();
        String oldClear = ned.cdbOper.exists(cleartextCP) ? ned.cdbOper.getElem(cleartextCP).toString() : null;
        if (ned.cdbOper.exists(encryptedCP)) {
            ned.cdbOper.delete(encryptedCP);
        }
        if (((Schema.Leaf)(leaf.node)).isTypeEmpty) {
            newClear = "EMPTY";
        }
        if (!newClear.equals(oldClear)) {
            ned.cdbOper.setElem(new ConfBuf(newClear), cleartextCP);
            ned.logDebug(worker, String.format("device-transformed-value update cleartext: %s - %s -> %s", keyPath, oldClear, newClear));
            ned.needDeviceTransformedValues = true;
            if (parserContext != null) {
                if (currentLineOfDependencies < parserContext.currentLineNumber) {
                    currentLineOfDependencies = parserContext.currentLineNumber;
                    Schema.Container parent = leaf.node.getMySubtree();
                    if (ned.deviceTransformedDeps.containsKey(parent)) {
                        // Clear all dependencies
                        for (Schema.Node dep : ned.deviceTransformedDeps.get(parent)) {
                            Schema.Container common = leaf.node.getCommonAncestor(dep);
                            String depRel = dep.xpath.substring(common.xpath.length());
                            Schema.TreeNode commonData = leaf.getAncestor(common);
                            String depKey = commonData.getKeyPath() + depRel;
                            depKey = depKey.replace('{', '_').replace('}', '_').replace(' ', '_');
                            ConfPath depCP = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/secrets:secrets/secret{%s}", ned.device_id, depKey));
                            if (ned.cdbOper.exists(depCP)) {
                                ConfPath depCleartextCP = depCP.copyAppend("cleartext");
                                if (ned.cdbOper.exists(depCleartextCP)) {
                                    ned.cdbOper.delete(depCleartextCP);
                                    ned.logDebug(worker, String.format("device-transformed-value, clear dependency '%s'", depKey));
                                }
                            }
                        }
                    }
                }
                for (Schema.TreeNode data : parserContext.currentLineData.getData()) {
                    if ((data instanceof Schema.TreeLeaf) &&
                        data.node.annotations.getSubStmt("tailf-ned-cisco-nx:device-transformed-dependency") != null) {
                        captureCleartext(worker, (Schema.TreeLeaf)data, null); // Ouch: quirk: pass null to just capture single data
                    }
                }
            }
        } else {
            ned.logDebug(worker, String.format("device-transformed-value cleartext: %s - %s", keyPath, newClear));
        }
    }

    private void captureEncrypted(NedWorker worker, Schema.TreeLeaf leaf, Schema.ParserContext parserContext) throws ConfException, IOException {
        String keyPath = leaf.getKeyPath();
        String newEncrypted = leaf.getValue();
        String operKey = keyPath.replace('{', '_').replace('}', '_').replace(' ', '_');
        ConfPath cp = secretsOperConfPath(leaf, true);
        ConfPath cleartextCP = cp.copyAppend("cleartext");
        ConfPath encryptedCP = cp.copyAppend("encrypted");
        String oldEncrypted = ned.cdbOper.exists(encryptedCP) ? ned.cdbOper.getElem(encryptedCP).toString() : null;
        String clear = ned.cdbOper.exists(cleartextCP) ? ned.cdbOper.getElem(cleartextCP).toString() : null;
        boolean isEmptyLeaf = ((Schema.Leaf)(leaf.node)).isTypeEmpty;
        if (!isEmptyLeaf && (ned.forceUpdateTransformedValues || (oldEncrypted == null))) {
            ned.logDebug(worker, String.format("device-transformed-value update encrypted: %s - %s", keyPath, newEncrypted));
            ned.cdbOper.setElem(new ConfBuf(newEncrypted), encryptedCP);
            oldEncrypted = newEncrypted;
        }
        if (isEmptyLeaf || ((clear != null) && newEncrypted.equals(oldEncrypted))) {
            ned.logDebug(worker, String.format("device-transformed-value replace with cleartext: %s - %s -> %s", keyPath, newEncrypted, clear));
            if (isEmptyLeaf) {
                if (clear == null) {
                    leaf.removeFromParent();
                    ned.logDebug(worker, String.format("device-transformed-dependency of type empty stripped: %s", leaf.getKeyPath()));
                }
            } else {
                leaf.setValue(clear);
            }
            if (ned.transformedValues != null)
            {
                // Not needed in showPartial, can't clean cdboper without full config
                ned.transformedValues.add(operKey);
            }
            if (parserContext != null) {
                for (Schema.TreeNode data : parserContext.currentLineData.getData()) {
                    if ((data instanceof Schema.TreeLeaf) &&
                        data.node.annotations.getSubStmt("tailf-ned-cisco-nx:device-transformed-dependency") != null) {
                        captureEncrypted(worker, (Schema.TreeLeaf)data, null); // Ouch: quirk: pass null to just capture single data
                    }
                }
            }
        } else {
            ned.logDebug(worker, String.format("device-transformed-value no cleartext or oob change of encrypted: %s - %s != %s", keyPath, oldEncrypted, newEncrypted));
        }
    }

    // "device-transformed-value"
    @SuppressWarnings("deprecation")
    public void deviceTransformedValue(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT) throws Exception {
        String keyPath = parserContext.currentDataContext.getKeyPath();
        if (!ned.hasBehaviour("cleartext-provisioning")) {
            // TODO: move out to when in model + fix general when-eval before call-back
            ned.logDebug(worker, String.format("Skip device-transformed-value in %s, 'cleartext-provisioning' disabled", keyPath));
            return;
        }
        if (!(parserContext.currentDataContext instanceof Schema.TreeLeaf)) {
            ned.logError(worker, String.format("device-transformed-value not supported on non-leaf: %s", keyPath));
            return;
        }
        Schema.TreeLeaf leaf = (Schema.TreeLeaf)parserContext.currentDataContext;
        if (parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            if (leaf.isDelete()) {
                Schema.TreeListEntry listEntry = leaf.getEnclosingListEntry();
                boolean doFetchTransformed = true;
                if (listEntry != null) {
                    doFetchTransformed = ned.maapi.exists(toT, listEntry.getNCSConfigKP(ned.device_id));
                    ned.logDebug(worker, String.format("Removing device-transformed-value in list entry%s %s",
                                                       !doFetchTransformed ? ", being removed" : "", listEntry.getKeyPath()));
                }
                ned.logDebug(worker, String.format("device-transformed-value clear: %s", keyPath));
                ned.needDeviceTransformedValues = doFetchTransformed;
            } else {
                captureCleartext(worker, leaf, parserContext);
            }
            boolean wasEncrypted = false;
            try {
                String line = parserContext.getCurrentLine();
                MaapiCrypto mCrypto = new MaapiCrypto(ned.maapi);
                String encrypted = parserContext.getLeaf(".").getValue();
                if ((encrypted.length() > 3) &&
                    (encrypted.charAt(0) == '$') &&
                    (encrypted.charAt(2) == '$') &&
                    ((encrypted.charAt(1) == '4') || (encrypted.charAt(1) == '8')|| (encrypted.charAt(1) == '9')))
                {
                    encrypted = encrypted.replace("\n", "");
                    String decrypted = mCrypto.decrypt(encrypted);
                    String orig = parserContext.getVerbatimValue();
                    line = line.replace(orig, decrypted);
                    parserContext.replaceCurrentLine(line);
                    wasEncrypted = true;
                }
            } catch (MaapiException e) {
                // Ignore, assume cleartext/device-native (encrypted/obfuscated) value
            }
            if (!wasEncrypted) {
                if (ned.hasBehaviour("cleartext-stored-encrypted")) {
                    throw new NedException(String.format("Couldn't decrypt value in %s, please ensure it is stored with an NSO encrypted type (e.g. 'tailf:aes-cfb-128-encrypted-string')",
                                                         parserContext.currentDataContext.getKeyPath()));
                } else {
                    ned.logDebug(worker, String.format("Found cleartext/device-encrypted/obfuscated value in %s",
                                                       parserContext.currentDataContext.getKeyPath()));
                }
            }
        } else {
            captureEncrypted(worker, leaf, parserContext);
        }
    }

    private Schema.TreeNode createListEntry(final NedWorker worker, final NavuListEntry entry, final int transId) throws Exception {
        final Schema.TreeContainer dataContext = schema.newDataTree();
        return createListEntry(worker, dataContext, entry, transId);
    }

    private Schema.TreeNode createListEntry(final NedWorker worker, final Schema.TreeContainer dataContext, final NavuListEntry entry, final int transId) throws Exception {
        String devKP = entry.getKeyPath();
        String dataKP = devKP.replaceAll("/ncs:devices/device\\{\\S+?\\}/config/\\S+:", "/");
        Schema.TreeNode dataEntry = dataContext.createTreeNode(dataKP);
        NavuUtils.traverse(entry, new NavuUtils.NavuTraverser() {
                public void visitLeaf(NavuLeaf navuLeaf) {
                    try {
                        String navuKP = navuLeaf.getKeyPath();
                        if (!navuLeaf.isKey() && ned.maapi.exists(transId, navuKP)) {
                            String leafKP = navuKP.replaceAll("/ncs:devices/device\\{\\S+?\\}/config/\\S+:", "/");
                            Schema.TreeLeaf dataLeaf = dataContext.createLeaf(leafKP);
                            if (!((Schema.Leaf)dataLeaf.node).isTypeEmpty) {
                                dataLeaf.setValue(navuLeaf.valueAsString());
                            }
                        }
                    } catch (Exception e) {
                        ned.logError(worker, "Error in 'explicit-ordered-by-user' navu access: "
                                     + e.getMessage(), e);
                    }
                }
                public boolean visitListEntry(NavuListEntry navuEntry) {
                    if (navuEntry != entry) {
                        throw new RuntimeException("Lists in lists not supported...");
                    }
                    return true;
                }
                public boolean visitContainer(NavuNode container) {
                    return true;
                }
            });
        return dataEntry;
    }

    // "explicit-ordered-by-user"
    public void explicitOrderedByUser(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String path = parserContext.getNCSCurrentKP(ned.device_id);
        String schemaPath = path.substring(0, path.lastIndexOf('{'));
        ned.logDebug(worker, "Handling explicit-ordered-by-user in : " + schemaPath);
        List<String> addLines = new ArrayList<>();
        List<String> remLines = new ArrayList<>();

        // Check for deletions once, must go first
        if (!ned.handledOBUDelete.contains(schemaPath)) {
            ned.handledOBUDelete.add(schemaPath);
            if (ned.maapi.exists(fromT, schemaPath)) {
                NavuList fromList = (NavuList)new NavuContainer(new NavuContext(ned.maapi, fromT)).
                    container(Ncs.hash).getNavuNode(new ConfPath(schemaPath));
                for (NavuContainer c : fromList.elements()) {
                    String navuKP = c.getKeyPath();
                    if (!ned.maapi.exists(toT, navuKP)) {
                        Schema.TreeNode dataEntry = createListEntry(worker, (NavuListEntry)c, fromT);
                        StringBuilder line = parserContext.currentIndent();
                        line.append("no");
                        line.append(parserContext.emitCLISingleLine(dataEntry));
                        remLines.add(line.toString());
                    }
                }
            }
        }

        if (!parserContext.currentDataContext.isDelete() && !ned.handledOBUEdit.contains(schemaPath)) {
            ned.handledOBUEdit.add(schemaPath);
            NavuListEntry current = (NavuListEntry)new NavuContainer(new NavuContext(ned.maapi, toT)).
                container(Ncs.hash).getNavuNode(new ConfPath(path));

            NavuList toList = (NavuList)new NavuContainer(new NavuContext(ned.maapi, toT)).
                container(Ncs.hash).getNavuNode(new ConfPath(schemaPath));
            boolean skip = true;
            for (NavuContainer c : toList.elements()) {
                final NavuListEntry entry = (NavuListEntry)c;
                String devKP = entry.getKeyPath();
                String dataKP = devKP.replaceAll("/ncs:devices/device\\{\\S+?\\}/config/\\S+:", "/");
                if (skip && !entry.getKey().equals(current.getKey())) {
                    ned.logDebug(worker, String.format("Skipping %s", dataKP));
                    continue;
                }
                skip = false;
                if (ned.maapi.exists(fromT, devKP)) {
                    ned.logDebug(worker, String.format("Deleteing %s", devKP));
                    NavuListEntry fromEntry = (NavuListEntry)
                        new NavuContainer(new NavuContext(ned.maapi, fromT)).
                        container(Ncs.hash).getNavuNode(new ConfPath(devKP));
                    StringBuilder line = parserContext.currentIndent();
                    line.append("no");
                    Schema.TreeNode dataEntry = createListEntry(worker, fromEntry, fromT);
                    line.append(parserContext.emitCLISingleLine(dataEntry));
                    remLines.add(line.toString());
                } else {
                    ned.logDebug(worker, String.format("New entry %s", devKP));
                }
                Schema.TreeNode dataEntry = createListEntry(worker, entry, toT);
                addLines.add(parserContext.emitCLISingleLine(dataEntry));
            }
        } else {
            ned.logDebug(worker, String.format("Skipping handled obu-diff: %s", path));
        }
        parserContext.skipCurrentLine();
        parserContext.injectBefore(remLines);
        parserContext.injectAfter(addLines);
    }

    // "trim-default-in-show"
    public void trimDefaultInShow(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        Schema.TreeLeaf leaf = (Schema.TreeLeaf)parserContext.currentDataContext;
        if (leaf.isDefault()) {
            parserContext.currentDataContext.removeFromParent();
            parserContext.skipCurrentLine();
        }
    }

    // "trim-default-in-diff"
    public void trimDefaultInDiff(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        Schema.TreeLeaf leaf = parserContext.getLeaf(".");
        if (!ned.hasBehaviour("trim-defaults-in-snmp-traps-diff") || !"0".equals(ned.nedSettings.getString("api/snmp-server-enable-all-traps"))) {
            return;
        }
        if (leaf.isDefault()) {
            String devKP = leaf.getNCSConfigKP(ned.device_id);
            boolean doTrim = false;
            if (ned.maapi.exists(fromT, devKP)) {
                String currentValue = ConfValue.getStringByValue(devKP, ned.maapi.getElem(fromT, devKP));
                doTrim = leaf.getValue().equals(currentValue);
            } else {
                doTrim = true;
            }
            if (doTrim) {
                parserContext.currentDataContext.removeFromParent();
                parserContext.skipCurrentLine();
            }
        }
    }

    // "split-interface-name"
    public void splitInterfaceName(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        String line = parserContext.getCurrentLine();
        Pattern p = Pattern.compile("([ \\S]+[/])(\\d+)[-](\\d+)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            parserContext.skipCurrentLine();
            int start = Integer.parseInt(m.group(2));
            int end = Integer.parseInt(m.group(3));
            for (int n = start; n <= end; n++) {
                String inject = String.format("%s%d", m.group(1), n);
                ned.traceVerbose(worker, "split-interface-name: injecting: '"+inject+"'");
                parserContext.injectImmediate(inject);
            }
        }
    }

    // "split-compact-line"
    public void splitCompactLine(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        String line = parserContext.getCurrentLine();
        String rest = parserContext.currentMatch.restOfLine.toString();
        String prefix = line.substring(0, line.length() - rest.length() - 1);
        rest = rest.trim();
        String find = metaData.argument;
        Pattern p = Pattern.compile(find);
        Matcher m = p.matcher(rest);
        if (!m.find()) {
            return;
        }
        int start = m.start();
        boolean didSplit = false;
        while (m.find()) {
            didSplit = true;
            int end = m.start();
            String split = String.format("%s %s", prefix, rest.substring(start, end-1));
            parserContext.injectImmediate(split);
            start = end;
        }
        if (didSplit) {
            ned.logDebug(worker, String.format("splitting compact line '%s'", line));
            parserContext.injectImmediate(prefix + " " + rest.substring(start));
            parserContext.skipCurrentLine();
        }
    }

    // "delete-rsa-key"
    public void deleteRsaKey(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        Schema.TreeLeaf leaf = (Schema.TreeLeaf)parserContext.currentDataContext;
        if (parserContext.currentDataContext.isDelete()) {
            StringBuilder line = parserContext.currentIndent();
            line.append(String.format("crypto key zeroize rsa %s", leaf.getValue()));
            parserContext.skipCurrentLine();
            parserContext.injectAfter(line.toString());
        }
    }

    // "dequote-input"
    public void dequoteInput(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (parserContext.currentDataContext instanceof Schema.TreeLeaf) {
            Schema.TreeLeaf leaf = (Schema.TreeLeaf)parserContext.currentDataContext;
            String strToDequote = leaf.getValue();
            strToDequote = stringDequote(strToDequote);
            leaf.setValue(strToDequote);
        }
    }

    // "dequote-output"
    public void dequoteOutput(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        String line = parserContext.getCurrentLine();
        if (line.indexOf('"') != -1) {
            String quoted = stringQuote(parserContext.getLeaf(".").getValue());
            String dequoted = stringDequote(quoted);
            line = line.replace(String.format("%s", quoted), dequoted);
            parserContext.replaceCurrentLine(line);
        }
    }

    // "escape-questionmark"
    public void escapeQuestionmark(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (ned.useCLI && !ned.isDevice("netsim")) {
            String line = parserContext.getCurrentLine();
            if (line.indexOf('?') != -1) {
                line = line.replace("?", "\u0016?");
                parserContext.replaceCurrentLine(line);
            }
        }
    }

    // no-to-disable
    public void noToDisable(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        String line = parserContext.getCurrentLine();
        if ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
            (parserContext.getState() == Schema.ParserState.POST_MATCH)) {
            if (line.trim().endsWith(" disable")) {
                StringBuilder indent = parserContext.currentIndent();
                if (!parserContext.isDelete()) {
                    indent.append("no ");
                } else {
                    line = line.replace("no ", "");
                }
                indent.append(line.replace("disable", "").trim());
                String disableLine = indent.toString();
                ned.logDebug(worker, String.format("disable to no %s (%s)", parserContext.getCurrentKeyPath(), disableLine));
                parserContext.replaceCurrentLine(disableLine);
            }
        } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                   (parserContext.getState() == Schema.ParserState.PRE_MATCH) &&
                   parserContext.isDelete()) {
            String disableLine = line.replace("no ", "") + " disable";
            ned.logDebug(worker, String.format("no to disable %s (%s)", parserContext.getCurrentKeyPath(), disableLine));
            parserContext.replaceCurrentLine(disableLine);
        }
    }

    // explicit-delete-additive
    public void explicitDeleteAdditive(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String line = parserContext.getCurrentLine();
        if (parserContext.isDelete() && !line.contains("additive")) {
            String parentKP = parserContext.currentDataContext.getParent().getKeyPath();
            String additivePath = schema.createNCSDeviceConfigKP(ned.device_id, parentKP) + "/additive";
            if (ned.maapi.exists(fromT, additivePath) && !ned.maapi.exists(toT, additivePath)) {
                line = line + " additive";
                parserContext.replaceCurrentLine(line);
            }
        }
    }

    // handle-allowed-vsan
    public void handleAllowedVsan(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
            (parserContext.getState() == Schema.ParserState.POST_MATCH)) {
            Schema.TreeLeafList vsan = parserContext.getLeafList(".");
            String[] ranges = vsan.getLeafListValue().split(",");
            StringBuilder prefix = parserContext.currentIndent();
            if (parserContext.isDelete()) {
                prefix.append("no switchport trunk allowed vsan");
            } else {
                prefix.append("switchport trunk allowed vsan add");
            }
            parserContext.replaceCurrentLine(String.format("%s %s", prefix, ranges[0]));
            if (ranges.length > 1) {
                Schema.ParserContext.InjectAfter injection = null;
                for (int i = 1; i < ranges.length; i++) {
                    String line = String.format("%s %s", prefix, ranges[i]);
                    if (injection == null) {
                        injection = parserContext.injectAfter(line);
                    } else {
                        injection.addLineLast(line);
                    }
                }
            }
        } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                   (parserContext.getState() == Schema.ParserState.PRE_MATCH) &&
                   (parserContext.currentMatch.restOfLine.indexOf("add ") >= 0)) {
            if (parserContext.currentMatch.restOfLine.charAt(0) == ' ') {
                parserContext.currentMatch.restOfLine.deletePrefix(1);
            }
            parserContext.currentMatch.restOfLine.deletePrefix(4);
        }
    }

    // dayzero-config
    @SuppressWarnings("deprecation")
    public void checkDayZeroCfg(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws NedException
    {
        if (!ned.hasBehaviour("dayzero-permit-write")) {
            throw new NedException("Day-zero config is write-protected, enable ned-setting 'dayzer-permit-write' to write it (see README)");
        }
    }

    // inject-vsan-in-db
    public void injectVsanInDb(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws NedException
    {
        String ckp = parserContext.getCurrentKeyPath();
        if (!injectedVsans.contains(ckp)) {
            injectedVsans.add(ckp);
            StringBuilder vsanLine = parserContext.currentIndent();
            Schema.TreeLeaf vsan = (Schema.TreeLeaf)parserContext.currentDataContext;
            vsanLine.append(String.format("vsan %s", vsan.getValue()));
            String line = parserContext.getCurrentLine();
            parserContext.skipCurrentLine();
            parserContext.injectImmediate(vsanLine.toString()); // vsan implicitly created (workaround NSO bug)
            parserContext.injectImmediate(line);
        }
    }

    // filter-non-config
    public void filterNonConfig(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        String line = parserContext.getCurrentLine();
        Pattern fromPat = Pattern.compile(metaData.argument);
        Matcher m = fromPat.matcher(line.trim());
        if (m.find()) {
            parserContext.replaceCurrentLine(m.replaceAll(""));
        }
    }

    // trim-default-key
    public void trimDefaultKey(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        String line = parserContext.getCurrentLine();
        String defaultKey = ((Schema.Leaf)metaData.node).defaultVal;
        if (!"".equals(metaData.node.token)) {
            defaultKey = String.format("%s %s", metaData.node.token, defaultKey);
        }
        Pattern keyPat = Pattern.compile(String.format("( *.+ )%s(?: (.+))?$", defaultKey));
        Matcher m = keyPat.matcher(line);
        if (m.find()) {
            line = m.group(1);
            if (m.group(2) != null) {
                line += m.group(2);
            }
            parserContext.replaceCurrentLine(line);
        }
    }

    // fix-per-module-lb
    public void fixPerModuleLB(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (!ned.isDevice("netsim")) {
            String line = parserContext.getCurrentLine();
            String origLine = line;
            boolean doReplace = false;
            // Re-order load-balance per module, syntax has module key last (to avoid struct change, could be done with prefix-keys)
            if ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
                (parserContext.getState() == Schema.ParserState.POST_MATCH)) {
                line = line.replaceAll("(\\s*(?:no )?port-channel load-balance) (module [0-9]+) (.*)", "$1 $3 $2");
            } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                       (parserContext.getState() == Schema.ParserState.PRE_MATCH)) {
                line = line.replaceAll("(\\s*port-channel load-balance) (\\S+.+) (module [0-9]+)", "$1 $3 $2");
            }
            if (!origLine.equals(line)) {
                parserContext.replaceCurrentLine(line);
            }
        }
    }

    // reset-terminal-length
    public void resetTerminalLength(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        boolean addExit = !parserContext.getCurrentLine().contains("exit");
        parserContext.injectAfter(String.format("%sterminal length 0", addExit ? "exit ; " : ""));
    }

    // toggle-hw-module-obfl
    public void toggleHWModuleOBFL(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String kp = parserContext.getCurrentKeyPath();
        if (hwmodOBFLToBeDisabled.contains(kp)) {
            parserContext.skipCurrentLine();
            return;
        }
        String maapiPath = parserContext.getNCSCurrentKP(ned.device_id);
        String module = ((Schema.TreeLeaf)parserContext.currentDataContext).getValue();
        if (!ned.maapi.exists(toT, maapiPath)) {
            hwmodOBFLToBeDisabled.add(kp);
            parserContext.replaceCurrentLine(String.format("no hw-module logging onboard module %s", module));
        } else if (!ned.maapi.exists(fromT, maapiPath) &&
                   !hwmodOBFLToBeEnabled.contains(kp)) {
            hwmodOBFLToBeEnabled.add(kp);
            parserContext.injectBefore(String.format("hw-module logging onboard module %s", module));
        }
    }

    // handle-sr-mpls-no-mode
    public void handleSRMPLSNoMode(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        String line = parserContext.getCurrentLine();
        if ("segment-routing".equals(line.trim())) {
            parserContext.replaceCurrentLine("");
        } else {
            parserContext.replaceCurrentLine("segment-routing mpls");
        }
    }

    // escape-backslash
    public void escapeBackslash(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        String line = parserContext.getCurrentLine();
        StringBuilder escaped = parserContext.currentIndent();
        boolean didEscape = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            escaped.append(c);
            if (c == '\\') {
                escaped.append(c);
                didEscape = true;
            }
        }
        if (didEscape) {
            ned.logDebug(worker, String.format("escaped backslash: '%s' with '%s'", line, escaped.toString()));
            parserContext.replaceCurrentLine(escaped.toString());
        }
    }

    // edit-not-supported
    @SuppressWarnings("deprecation")
    public void editNotSupported(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        throw new NedException(String.format("The config in '%s' is currently not supported to edit via NED",
                                             parserContext.getCurrentKeyPath()));
    }

    // fix-range-syntax (dynamically mapped to tailf:cli-range-list-syntax)
    private final static Pattern numCommaPat = Pattern.compile("(\\d)\\,(\\s+)(?=\\d)");
    public void fixRangeSyntax(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        // Remove space in syntax for cli-range-list-syntax
        parserContext.currentMatch.restOfLine.replaceAll(numCommaPat, "$1,");
    }

    // handle-no-list
    public void handleNoList(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        if (parserContext.isDelete()) {
            parserContext.currentMatch.invertDelete();
            parserContext.currentMatch.restOfLine.insert(0, "no-list ");
        }
    }

    // show-no-disable
    public void showNoDisable(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        if (parserContext.isDelete()) {
            // Show-no on trimmed default (i.e. "tri-state")
            parserContext.currentMatch.invertDelete();
            parserContext.currentMatch.restOfLine.append(" disable");
        }
    }

    // tri-state-boolean
    public void triStateBoolean(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        if ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
            (parserContext.getState() == Schema.ParserState.POST_MATCH)) {
            String line = parserContext.getCurrentLine();
            if (parserContext.isDelete()) {
                StringBuilder newLine = parserContext.currentIndent();
                newLine.append("default ");
                newLine.append(line.trim().substring(3, line.lastIndexOf(' ')));
                parserContext.replaceCurrentLine(newLine.toString());
            } else if (line.endsWith("true")) {
                parserContext.replaceCurrentLine(line.substring(0, line.lastIndexOf(' ')));
            } else {
                StringBuilder newLine = parserContext.currentIndent();
                newLine.append("no ");
                newLine.append(line.trim().substring(0, line.lastIndexOf(' ')));
                parserContext.replaceCurrentLine(newLine.toString());
            }
        } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                   (parserContext.getState() == Schema.ParserState.PRE_MATCH)) {
            if (parserContext.isDelete()) {
                // 'no ...' -> false
                parserContext.currentMatch.invertDelete();
                parserContext.currentMatch.restOfLine.append(" false");
            } else {
                parserContext.currentMatch.restOfLine.append(" true");
            }
        }
    }

    // handle-allowed-vlan
    // NOTE: To keep legacy structure (+ allow vtsPrivateFeatures), don't change !
    // What the below does:
    // 1) insert 'add' keyword into all allowed-lines except when leaf-list created
    //
    // 2) no 'add' after a 'none'-keyword is deleted (i.e. correct syntax, same as above but 'none' was set before, for vtsPrivateFeatures when choice is removed from model)
    //
    // 3) insert 'remove' keyword for all removed vlan id's
    //
    public void handleAllowedVlan(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
            (parserContext.getState() == Schema.ParserState.POST_MATCH)) {
            if (!ned.debugPrepare && ned.isDevice("netsim")) {
                return;
            }
            String line = parserContext.getCurrentLine();
            if (!line.trim().endsWith("none")) {
                String maapiPath = parserContext.getNCSCurrentKP(ned.device_id) + "/ids";
                if (parserContext.isDelete()) {
                    if (ned.maapi.exists(toT, maapiPath)) {
                        line = line.replace("no ", "").replace(" vlan ", " vlan remove ");
                        parserContext.replaceCurrentLine(line);
                    }
                } else if (ned.maapi.exists(fromT, maapiPath)) {
                    line = line.replace(" vlan ", " vlan add ");
                    parserContext.replaceCurrentLine(line);
                }
            }
        } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                   (parserContext.getState() == Schema.ParserState.PRE_MATCH) &&
                   (parserContext.currentMatch.restOfLine.indexOf("add ") >= 0)) {
            if (parserContext.currentMatch.restOfLine.charAt(0) == ' ') {
                parserContext.currentMatch.restOfLine.deletePrefix(1);
            }
            parserContext.currentMatch.restOfLine.deletePrefix(4);
        }
    }

    // fix-send-community
    @SuppressWarnings("deprecation")
    public void fixSendCommunity(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) throws Exception {
        if (changeExtendedToBoth) {
            parserContext.currentMatch.restOfLine.replace("extended", "both");
            changeExtendedToBoth = false;
        } else if (!parserContext.isDelete() &&
                   (parserContext.currentMatch.restOfLine.toString().trim().length() == 0) &&
                   "send-community extended".equals(parserContext.peekNextLine().trim())) {
            changeExtendedToBoth = true;
            parserContext.skipCurrentLine();
        }
    }

    // expand-bridge-domain
    private List<String> bdRange;
    private List<String> vniRange;
    private String bdLine;
    // Split bridge-domain ranges into discrete list entries. The
    // device accepts discrete list entries, but displays
    // condensed ranges, so NCS is using the discrete model with
    // the conversion below. Example:
    //
    // bridge-domain 555-556,666
    //   member vni 55555-55556, 66666
    // ...becomes:
    // bridge-domain 555
    //   member vni 55555
    // bridge-domain 556
    //   member vni 55556
    // bridge-domain 666
    //   member vni 66666
    public void expandBridgeDomain(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (parserContext.getState() == Schema.ParserState.PRE_MATCH) {
            if ((parserContext.currentMatch.restOfLine.indexOf(',') > -1) ||
                (parserContext.currentMatch.restOfLine.indexOf('-') > -1)) {
                String rangeStr = parserContext.currentMatch.restOfLine.toString().trim();
                ned.logDebug(worker, String.format("expand-bridge-domain range '%s'", rangeStr));
                bdRange = NexusNedCli.expandRanges(rangeStr);
                bdLine = parserContext.peekNextLine();
                String bdIDZero = bdRange.remove(0);
                parserContext.currentMatch.restOfLine.replaceRest(bdIDZero);
            }
        } else if (bdRange != null) {
            if (parserContext.getState() == Schema.ParserState.POST_CTX_MATCH) {
                if (!"exit".equals(parserContext.peekNextLine().trim())) {
                    // Make sure we get explicit exit, don't want implicit here
                    parserContext.injectImmediate("exit");
                }
            } else {
                int vniId = -1;
                if (vniRange != null) {
                    vniId = 0;
                }
                List<String> lines = new ArrayList<>();
                for (String bdId : bdRange) {
                    if ((vniId > -1) && (vniRange != null)) {
                        bdLine = String.format("  member vni %s", vniRange.get(vniId));
                        vniId += 1;
                    }
                    lines.add(String.format("bridge-domain %s", bdId));
                    lines.add(bdLine);
                    lines.add("exit");
                }
                ned.logDebug(worker, String.format("expand-bridge-domain inject after %s:", parserContext.getCurrentKeyPath()));
                for (String l : lines) {
                    ned.logDebug(worker, "  " + l);
                }
                parserContext.injectImmediate(lines);
                vniRange = null;
                bdRange = null;
                bdLine = null;
            }
        }
    }

    // expand-bd-member-vni
    public void expandBDMemberVni(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if ((parserContext.currentMatch.restOfLine.indexOf(',') > -1) ||
            (parserContext.currentMatch.restOfLine.indexOf('-') > -1)) {
            String rangeStr = parserContext.currentMatch.restOfLine.toString().trim();
            ned.logDebug(worker, String.format("expand-bd-member-vni expands '%s'", rangeStr));
            vniRange = NexusNedCli.expandRanges(rangeStr);
            String vniIDZero = vniRange.remove(0);
            parserContext.currentMatch.restOfLine.replaceRest(vniIDZero);
        }
    }

    // scheduler-job-commands
    public void schedulerJobCommands(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if (parserContext.getState() == Schema.ParserState.PRE_MATCH) {
            parserContext.startMultiLine(metaData);
        } else if (parserContext.getCurrentLine().trim().equals("end-job")) {
            List<String> multiLines = parserContext.endMultiLine();
            StringBuilder commands = new StringBuilder();
            multiLines.remove(multiLines.size()-1); // end-job is not part of commands
            for (String l : multiLines) {
                l = l.trim();
                if (l.length() > 0) {
                    if (commands.length() > 0) {
                        commands.append(" ;");
                    }
                    commands.append(l);
                }
            }
            schema.addData(parserContext.currentDataContext,
                               (Schema.Leaf)metaData.node, commands.toString());
            parserContext.injectImmediate("end-job"); // to exit context
        }
    }

    // dot1q-vni-mappings
    @SuppressWarnings("deprecation")
    public void dot1qVniMappings(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) throws Exception {
        if ((parserContext.currentMatch.restOfLine.indexOf(',') > -1) ||
            (parserContext.currentMatch.restOfLine.indexOf('-') > -1)) {
            String[] ranges = parserContext.currentMatch.restOfLine.toString().split(" vni ");
            List<String> dot1qRange = NexusNedCli.expandRanges(ranges[0].trim());
            List<String> vniMapRange = NexusNedCli.expandRanges(ranges[1].trim());
            if (dot1qRange.size() != vniMapRange.size()) {
                throw new NedException(String.format("Error when processing '%s' dot1q list mismatch: '%s' vni %s", parserContext.getCurrentKeyPath(), ranges[0].trim(), ranges[1].trim()));
            }
            String l0 = String.format("%s vni %s", dot1qRange.remove(0), vniMapRange.remove(0));
            parserContext.currentMatch.restOfLine.replaceRest(l0);
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < dot1qRange.size(); i++) {
                StringBuilder line = parserContext.currentIndent();
                line.append("dot1q ");
                line.append(dot1qRange.get(i));
                line.append(" vni ");
                line.append(vniMapRange.get(i));
                lines.add(line.toString());
            }
            parserContext.injectImmediate(lines);
        }
    }

    // iface-encap-profile-dot1q
    private final static Pattern ifaceEPDot1qPat = Pattern.compile("\\S+\\s+dot1q\\s+([0-9,\\s\\-]+)\\s*");
    public void ifaceEncapProfDot1q(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        Matcher m = ifaceEPDot1qPat.matcher(parserContext.currentMatch.restOfLine);
        if (m.matches()) {
            List<String> dot1qRange = NexusNedCli.expandRanges(m.group(1));
            parserContext.currentMatch.restOfLine.replaceAll("(\\S+)\\s+dot1q.*", "$1");
            List<String> lines = new ArrayList<>();
            for (String vlan : dot1qRange) {
                StringBuilder line = parserContext.currentIndent();
                line.append("  dot1q ");
                line.append(vlan);
                lines.add(line.toString());
            }
            parserContext.injectImmediate(lines);
        }
    }
    // iface-breakout-port-range
    private final static Pattern ifaceBOPortRangePat = Pattern.compile("([0-9,\\s\\-]+)(\\s+map \\S+)");
    public void ifaceBreakoutPortRange(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        Matcher m = ifaceBOPortRangePat.matcher(parserContext.currentMatch.restOfLine);
        if (m.matches()) {
            List<String> portRange = NexusNedCli.expandRanges(m.group(1));
            String map = m.group(2);
            String l0 = String.format("%s%s", portRange.remove(0), map);
            parserContext.currentMatch.restOfLine.replaceRest(l0);
            if (portRange.size() > 0) {
                String prefix = parserContext.currentMatch.restOfLine.getMatched();
                List<String> lines = new ArrayList<>();
                for (String port : portRange) {
                    StringBuilder line = parserContext.currentIndent();
                    line.append(prefix);
                    line.append(port);
                    line.append(map);
                    lines.add(line.toString());
                }
                parserContext.injectImmediate(lines);
            }
        }
    }

    // enum-alias
    public void enumAlias(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        String value = metaData.argument;
        String alias = metaData.extArguments;
        if (parserContext.currentMatch.restOfLine.startsWith(alias)) {
            parserContext.currentMatch.restOfLine.replace(alias, value);
        }
    }

    // "inject-before"
    public void injectBefore(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        parserContext.injectBefore(metaData.argument);
    }

    // "inject-all-allowed-vlans"
    public void injectAllAllowedVlans(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext) {
        if ("trunk".equals(parserContext.currentDataContext.value("switchport/mode")) &&
            !parserContext.currentDataContext.contains("switchport/trunk/allowed/vlan")) {
            Schema.TreeLeafList ids = parserContext.createLeafList("switchport/trunk/allowed/vlan/ids");
            ids.setLeafListValue("1-4094");
        }
    }


    // "obu-redeploy-on-edit"
    private int obuRedeployLinesToParse = 0;
    public void obuRedeployOnEdit(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String schemaPath = parserContext.getNCSCurrentKP(ned.device_id);
        schemaPath = schemaPath.substring(0, schemaPath.lastIndexOf('{')); // want schema list node, not list entry
        if (!ned.handledOBUEdit.contains(schemaPath)) {
            ned.handledOBUEdit.add(schemaPath);
            boolean needRedeploy = false;
            // Check for embedded delete and/or reorder
            ned.logDebug(worker, String.format("Checking obu-redeploy: %s", parserContext.getCurrentKeyPath()));
            if (ned.maapi.exists(fromT, schemaPath)) {
                NavuList fromList = (NavuList)new NavuContainer(new NavuContext(ned.maapi, fromT)).
                    container(Ncs.hash).getNavuNode(new ConfPath(schemaPath));
                NavuList toList = (NavuList)new NavuContainer(new NavuContext(ned.maapi, toT)).
                    container(Ncs.hash).getNavuNode(new ConfPath(schemaPath));
                Iterator<NavuContainer> fromListIter = fromList.elements().iterator();
                Iterator<NavuContainer> toListIter = toList.elements().iterator();
                boolean slottedUpdate = true;
                Set<String> changedKPs = new HashSet<>();

                // Collect all changed entries
                ned.maapi.diffIterate(toT,
                                      new MaapiDiffIterate() {
                                          public DiffIterateResultFlag iterate(ConfObject[] kp,
                                                                               DiffIterateOperFlag op,
                                                                               ConfObject old_value,
                                                                               ConfObject new_value,
                                                                               Object initstate) {
                                              changedKPs.add((new ConfPath(kp)).toString());
                                              return DiffIterateResultFlag.ITER_RECURSE;
                                          }
                                      }, schemaPath);

                String firstChangedFrom = null;
                String firstChangedTo = null;
                while (fromListIter.hasNext() && toListIter.hasNext()) {
                    NavuListEntry fromE = (NavuListEntry)fromListIter.next();
                    NavuListEntry toE = (NavuListEntry)toListIter.next();
                    ned.logDebug(worker, String.format("Checking %s = %s", fromE.getKey(), toE.getKey()));
                    if (!fromE.getKey().equals(toE.getKey())) {
                        needRedeploy = true;
                        // If only new entries in "slot" of removed entries
                        slottedUpdate = slottedUpdate &&
                            !ned.maapi.exists(toT, fromE.getKeyPath()) &&
                            !ned.maapi.exists(fromT, toE.getKeyPath());
                    } else if (changedKPs.contains(toE.getKeyPath())) {
                        // entry updated, can't do slotted update if need to redeploy
                        slottedUpdate = false;
                    }
                    if ((firstChangedFrom == null) && (needRedeploy || !slottedUpdate)) {
                        firstChangedFrom = fromE.getKeyPath();
                        firstChangedTo = toE.getKeyPath();
                    }
                }
                boolean skippedUntilFirst = false;
                if (needRedeploy) {
                    ned.logDebug(worker, String.format("Do %s of: %s", slottedUpdate ? "'slot-insert' edit" : "full redeploy", parserContext.getCurrentKeyPath()));
                    List<String> redeployLines = new ArrayList<>();
                    Schema.Ystmt ystmt = metaData.node.annotations.getSubStmt(schema.module.name + ":" + "redeploy-dependency");
                    String xpath = (ystmt != null) ? ystmt.arg : null;
                    for (NavuContainer e : fromList.elements()) {
                        String currKP = e.getKeyPath();
                        if (!skippedUntilFirst) {
                            if (!firstChangedFrom.equals(currKP)) {
                                // Skip entries until first change
                                continue;
                            }
                            skippedUntilFirst = true;
                        }
                        if (slottedUpdate && ned.maapi.exists(toT, currKP)) {
                            continue;
                        }
                        Schema.TreeNode dataEntry = createListEntry(worker, (NavuListEntry)e, fromT);
                        StringBuilder line = parserContext.currentIndent();
                        line.append("no ");
                        line.append(parserContext.emitCLISingleLine(dataEntry, false)); // explicit delete, i.e. no children
                        redeployLines.add(line.toString());

                        //
                        if ((xpath != null) && ned.maapi.exists(toT, schemaPath)) {
                            String k = ((NavuListEntry)e).getKey().toString();
                            k = k.replace('{', '\'');
                            k = k.replace('}', '\'');
                            String xp = String.format(xpath, k);
                            int redepBeforeSz = redeployTargets.size();
                            ned.maapi.xpathEval(toT, new MaapiXPathEvalResult() {
                                    public XPathNodeIterateResultFlag result(ConfObject[] kp,
                                                                             ConfValue value,
                                                                             Object state) {
                                        String kps = (new ConfPath(kp)).toString();
                                        kps = kps.substring(0, kps.lastIndexOf('{'));
                                        redeployTargets.add(kps);
                                        ned.logDebug(worker, String.format("Add redeployTarget %s", kps));
                                        return XPathNodeIterateResultFlag.ITER_CONTINUE;
                                    }
                                }, null,
                                xp,
                                null,
                                schemaPath);
                            if ((redepBeforeSz == redeployTargets.size()) && ned.maapi.exists(fromT, schemaPath)) {
                                ned.maapi.xpathEval(fromT, new MaapiXPathEvalResult() {
                                        // Assure dependency for removals too
                                        public XPathNodeIterateResultFlag result(ConfObject[] kp,
                                                                                 ConfValue value,
                                                                                 Object state) {
                                            String kps = (new ConfPath(kp)).toString();
                                            kps = kps.substring(0, kps.lastIndexOf('{'));
                                            redeployTargets.add(kps);
                                            ned.logDebug(worker, String.format("Add redeployTarget (removed) %s", kps));
                                            return XPathNodeIterateResultFlag.ITER_CONTINUE;
                                        }
                                    }, null,
                                    xp,
                                    null,
                                    schemaPath);
                            }
                        }
                    }
                    skippedUntilFirst = false;
                    for (NavuContainer e : toList.elements()) {
                        String currKP = e.getKeyPath();
                        if (!skippedUntilFirst) {
                            if (!firstChangedTo.equals(currKP)) {
                                // Skip entries until first change
                                continue;
                            }
                            skippedUntilFirst = true;
                        }
                        if (slottedUpdate && ned.maapi.exists(fromT, currKP)) {
                            continue;
                        }
                        Schema.TreeNode dataEntry = createListEntry(worker, (NavuListEntry)e, toT);
                        StringBuilder line = parserContext.currentIndent();
                        // Force trim as default default-handling, NSO cli emit emits defaults in seq (regardless if set or not)
                        line.append(parserContext.emitCLISingleLine(dataEntry, true, Schema.DefaultHandlingMode.TRIM));
                        redeployLines.add(line.toString());
                    }
                    ned.handledOBUDelete.add(schemaPath);
                    parserContext.skipCurrentLine();
                    parserContext.injectImmediate(redeployLines);
                    obuRedeployLinesToParse = redeployLines.size();
                }
            }
        } else if (ned.handledOBUDelete.contains(schemaPath)) {
            if (obuRedeployLinesToParse == 0) {
                parserContext.skipCurrentLine();
            } else {
                obuRedeployLinesToParse -= 1;
            }
        }
    }

    // redeploy-handler
    public void redeployHandler(final NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if (parserContext.getState() == Schema.ParserState.POST_MATCH) {
            String kp = parserContext.getNCSCurrentKP(ned.device_id);
            kp = kp.substring(0, kp.lastIndexOf('{'));
            if (!redeployReplaces.containsKey(kp)) {
                redeployReplaces.put(kp, new ArrayList<>());
            }
            StringBuilder line = parserContext.currentIndent();
            line.append("! obu-list reorder moved: " + parserContext.getCurrentLine());
            redeployReplaces.get(kp).add(parserContext.replaceCurrentLine(line.toString()));
        } else {
            boolean isFirst = (parserContext.getState() == Schema.ParserState.FIRST_ENTER_CONTEXT);
            if (isFirst) {
                for (String targetKP : redeployReplaces.keySet()) {
                    boolean isNew = !ned.maapi.exists(fromT, targetKP);
                    if (!redeployTargets.contains(targetKP) || isNew) {
                        for (Schema.ParserContext.Replace replace : redeployReplaces.get(targetKP)) {
                            replace.setReplace(replace.origLine());
                        }
                        if (isNew) {
                            redeployTargets.remove(targetKP);
                        }
                    }
                }
            }
            for (String target : redeployTargets) {
                int useT = isFirst ? fromT : toT;
                boolean isDeleted = !ned.maapi.exists(toT, target);
                if (!isFirst && isDeleted) {
                    ned.logDebug(worker, String.format("Skip redeploy in %s since deleted", target.substring(0, target.lastIndexOf("/"))));
                    continue;
                }
                NavuList list = (NavuList)new NavuContainer(new NavuContext(ned.maapi, useT)).
                    container(Ncs.hash).getNavuNode(new ConfPath(target));
                Schema.TreeContainer top = schema.newDataTree();
                if (!list.isEmpty()) {
                    for (NavuContainer e : list.elements()) {
                        Schema.TreeNode le = createListEntry(worker, top, (NavuListEntry)e, useT);
                        if (isFirst) {
                            le.setDelete(true);
                        }
                    }
                    parserContext.injectBefore(parserContext.emitCLI(top));
                }
            }
        }
    }

    public void snmpServerAllTraps(final NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        if (ned.isDevice("netsim")) {
            // not relevant to netsim
            return;
        }
        String line = parserContext.getCurrentLine();
        NedState curState = ned.getCurrentState();
        if (parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            if (parserContext.getState() == Schema.ParserState.POST_MATCH) {
                parserContext.replaceCurrentLine(line.replace("all-", ""));
                if (curState == NedState.APPLY) {
                    ned.needDeviceTransformedValues = true;
                    ned.needSnmpTraps = (line.trim().startsWith("no ") ? -1 : 1);
                    if (ned.needSnmpTraps == 1) {
                        String indent = parserContext.currentIndent().toString();
                        parserContext.injectBefore(indent + "no snmp-server enable traps");
                    }
                }
            }
        } else {
            if (parserContext.getState() == Schema.ParserState.POST_MATCH) {
                snmpTraps.add(line.trim());
                parserContext.skipCurrentLine();
            } else {
                StringBuilder currSnmpConfig = new StringBuilder();
                int currCnt = 0;
                for (String l : snmpTraps) {
                    currSnmpConfig.append(l);
                    currSnmpConfig.append("\n");
                    if (!l.startsWith("no ")) {
                        currCnt += 1;
                    }
                }
                boolean isShow = (curState == NedState.SHOW) || (curState == NedState.SHOW_PARTIAL);
                boolean injectAllTraps = false;
                boolean doStore = ned.needSnmpTraps != 0;
                if (!doStore) {
                    String lastSnmpConfig = ned.readCompressedLeaf("snmp-traps-config");
                    String[] lastSnmpConfigLines = null;
                    if (isShow && (lastSnmpConfig != null)) {
                        lastSnmpConfigLines = lastSnmpConfig.split("\n");
                        Schema.TreeDiff diff =
                            schema.diff(Arrays.asList(lastSnmpConfigLines),
                                        snmpTraps,
                                        Schema.ParserDirection.FROM_DEVICE);
                        if (!diff.isEmpty()) {
                            StringBuilder diffTxt = diff.show(Schema.DiffFormat.TREE);
                            String msg = String.format("diff from set snmp-server enable traps:\n%s", diffTxt);
                            reportProgress(ned, NedProgress.Verbosity.VERBOSE, msg);
                        }
                    }
                    int trapCntMinMax = ned.nedSettings.getInt("api/snmp-server-enable-all-traps");
                    if (trapCntMinMax > 0) {
                        injectAllTraps = (currCnt >= trapCntMinMax);
                    } else if (lastSnmpConfigLines != null) {
                        int lastCnt = 0;
                        for (String l : lastSnmpConfigLines) {
                            if (!l.startsWith("no ")) {
                                lastCnt += 1;
                            }
                        }
                        if (currCnt > lastCnt) {
                            injectAllTraps = true;
                            doStore = true;
                        } else if (currCnt > 0) {
                            injectAllTraps = ((lastCnt - currCnt) < -trapCntMinMax);
                        }
                        ned.logDebug(worker, String.format("snmpServerAllTraps: lastCnt=%d, currCnt=%d", lastCnt, currCnt));
                    }
                } else {
                    injectAllTraps = ned.needSnmpTraps > 0;
                }
                ned.logDebug(worker, String.format("snmpServerAllTraps: inject=%s, isShow=%s, needTraps=%d, doStore=%s", injectAllTraps, isShow, ned.needSnmpTraps, doStore));
                ned.needSnmpTraps = 0;
                if (injectAllTraps) {
                    parserContext.injectImmediate("snmp-server enable all-traps");
                    parserContext.createLeaf("/snmp-server/enable/all-traps");
                }
                if (doStore) {
                    ned.writeCompressedLeaf(currSnmpConfig.toString(), "snmp-traps-config");
                }
            }
        }
    }

    public void prefixKeyLeafList(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        String line = parserContext.getCurrentLine();
        if ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
            (parserContext.getState() == Schema.ParserState.POST_MATCH)) {
            line = line.replaceAll(String.format("(.*)( %s \\S+)( .*)", metaData.argument), "$1$3$2");
            parserContext.replaceCurrentLine(line);
        } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                   (parserContext.getState() == Schema.ParserState.PRE_MATCH)) {
            parserContext.currentMatch.restOfLine.replaceAll(String.format("(.* )(%s \\S+)", metaData.argument), "$2 $1");
        }
    }

    public void caseInsensitiveType(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        if (!ned.hasBehaviour("support-case-insensitive-type")  ||
            ((parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) &&
             (ned.getCurrentState() != NedState.APPLY))) {
            return;
        }
        if (parserContext.currentDataContext instanceof Schema.TreeLeafList) {
            Schema.TreeNode cdc = parserContext.currentDataContext;
            for (Schema.TreeNode l : parserContext.getLeafList(".").getSequence()) {
                parserContext.currentDataContext = l;
                caseInsensitiveType(worker, metaData, parserContext);
            }
            parserContext.currentDataContext = cdc;
            return;
        }
        Schema.TreeLeaf leaf = parserContext.getLeaf(".");
        String valueLowerCase = leaf.getValue().toLowerCase();
        String kp = parserContext.getCurrentKeyPath();
        if (leaf.isKey()) {
            // to get case-insensitive kp
            kp = kp.replace(leaf.getValue(), valueLowerCase);
        }
        if (leaf.node instanceof Schema.LeafList) {
            kp += "#" + valueLowerCase;
        }
        ConfPath cp = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/ncs:ned-settings/cisco-nx-oper/node-map{\"%s\"}", ned.device_id, kp));
        boolean exists = ned.cdbOper.exists(cp);
        if (parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            if (!leaf.isDelete()) {
                if (!exists) {
                    ned.cdbOper.create(cp);
                }
                ned.cdbOper.setElem(new ConfBuf(leaf.getValue()), cp.copyAppend("value"));
                ned.logDebug(worker, String.format("Stored case-insensitive value '%s' in %s", leaf.getValue(), kp));
            } else if (exists) {
                ned.cdbOper.delete(cp);
            }
        } else {
            cp = cp.copyAppend("value");
            String value = ned.cdbOper.exists(cp) ? ned.cdbOper.getElem(cp).toString() : null;
            if (value != null) {
                ned.logDebug(worker, String.format("Replace case-insensitive value '%s' with '%s' in %s", leaf.getValue(), value, kp));
                if (value.toLowerCase().equals(valueLowerCase)) {
                    leaf.setValue(value);
                }
                if (ned.caseInsensitiveValues != null)
                {
                    // Not needed in showPartial, can't clean cdboper without full config
                    ned.caseInsensitiveValues.add(kp);
                }
            }
        }
    }


    // vrf-member-chg-retain-l3
    public void vrfMemberChgRetainL3(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        // Device will not flush, disable redundant redeploy
        if (ned.vrfMemberL3Redeploy) {
            ned.logInfo(worker, "Disabling 'vrf-member-l3-redeploy' since device has 'system vrf-member-change retain-l3-config' set");
        }
        ned.vrfMemberL3Redeploy = false;
    }

    // comma-separated-list
    private String lastLinePrefix = null;
    public void commaSeparatedList(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        if (parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            if (parserContext.getState() == Schema.ParserState.PRE_MATCH) {
                lastLinePrefix = parserContext.restOfLine().getMatched();
            } else if (parserContext.getState() == Schema.ParserState.POST_MATCH) {
                Schema.TreeLeafList values = parserContext.getLeafList(".");
                boolean first = true;
                StringBuilder line = parserContext.currentIndent();
                line.append(lastLinePrefix);
                for (Schema.TreeNode val : values.getSequence()) {
                    if (parserContext.isDelete() != val.isDelete()) {
                        continue;
                    }
                    if (!first) {
                        line.append(", ");
                    }
                    first = false;
                    line.append(((Schema.TreeLeaf)val).getValue());
                }
                parserContext.replaceCurrentLine(line.toString());
            }
        } else if ((parserContext.parserDirection == Schema.ParserDirection.FROM_DEVICE) &&
                   (parserContext.getState() == Schema.ParserState.PRE_MATCH)) {
                parserContext.restOfLine().replaceAll(",", "");
        }
    }

    // reactivate-zoneset
    public void reactivateZoneset(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        String path = parserContext.getNCSCurrentKP(ned.device_id);
        if (ned.maapi.exists(toT, path)) {
            String name = parserContext.getLeaf("name").getValue();
            String vsan = parserContext.getLeaf("vsan").getValue();
            ConfPath activate = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/config/nx:zoneset/activate/name{%s %s}", ned.device_id, name, vsan));
            if (ned.maapi.exists(fromT, activate) && ned.maapi.exists(toT, activate)) {
                zonesetsToActivate.add(String.format("zoneset activate name %s vsan %s", name, vsan));
            }
        }
    }

    // commit-zone
    public void commitZone(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        String vsan = parserContext.getLeaf("vsan").getValue();
        ConfPath enhancedMode = new ConfPath(String.format("/ncs:devices/ncs:device{%s}/config/nx:zone/mode/enhanced/vsan{%s}", ned.device_id, vsan));
        if (ned.maapi.exists(toT, enhancedMode)) {
            vsansToCommit.add(vsan);
        }
    }

    // auto-reset-shutdown
    public void autoResetShutdown(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        // inject current shutdown when switchport toggle without explicit shutdown
        if ((parserContext.currentDataContext.getLeaf("shutdown") == null) &&
            (parserContext.currentDataContext.getLeaf("enable/switchport") != null)) {
            String path = parserContext.getNCSCurrentKP(ned.device_id) + "/shutdown";
            boolean shutdown = ned.maapi.exists(toT, path);
            StringBuilder line = parserContext.currentIndent();
            if (!shutdown) {
                line.append("no ");
            }
            line.append("shutdown");
            parserContext.injectBefore(line.toString());
        }
    }

    // before-exit-configure
    public void beforeExitConfigure(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        if (!ned.debugPrepare && ned.isDevice("netsim")) {
            return;
        }
        for (String zonesetActivate : zonesetsToActivate) {
            parserContext.injectAfter(zonesetActivate);
        }
        for (String vsan : vsansToCommit) {
            parserContext.injectAfter(String.format("zone commit vsan %s", vsan));
        }
    }

    // inject-on-enter-exit-mode
    public void injectOnEnterExitMode(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext, int fromT, int toT)
        throws Exception
    {
        NexusNedCli.InjectOnEnterExitMode injectMetaData = (NexusNedCli.InjectOnEnterExitMode)metaData;
        if (injectMetaData.devId.equals(ned.device_id)) {
            // Since we inject these possibly per device, need to check device_id
            String kp = parserContext.getNCSCurrentKP(ned.device_id);
            if (ned.maapi.exists(fromT, kp)) {
                // Check that we're editing the mode, i.e. no inject on create
                if ((injectMetaData.key == null) ||
                    kp.endsWith(injectMetaData.key + "}")) {
                    if (parserContext.getState() == Schema.ParserState.FIRST_ENTER_CONTEXT) {
                        if (metaData.argument != null) {
                            String line = parserContext.indent(metaData.argument).toString();
                            parserContext.injectAfter(line);
                        }
                    } else if (metaData.extArguments != null) {
                        String line = parserContext.indent(metaData.extArguments).toString();
                        parserContext.injectBefore(line);
                    }
                }
            }
        }
    }

    // filter-in-show
    public void filterInShow(NedWorker worker, Schema.CallbackMetaData metaData, Schema.ParserContext parserContext)
        throws Exception
    {
        parserContext.skipCurrentLine();
    }

}
