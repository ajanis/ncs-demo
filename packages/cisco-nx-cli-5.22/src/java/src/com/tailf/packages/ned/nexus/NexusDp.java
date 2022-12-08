package com.tailf.packages.ned.nexus;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfBool;
import com.tailf.conf.ConfKey;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.dp.DpCallbackException;
import com.tailf.dp.DpTrans;
import com.tailf.dp.annotations.DataCallback;
import com.tailf.dp.annotations.TransCallback;
import com.tailf.dp.proto.DataCBType;
import com.tailf.dp.proto.TransCBType;
import com.tailf.maapi.Maapi;
import com.tailf.ncs.ns.Ncs;
import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuException;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuLeaf;
import com.tailf.navu.NavuList;
import com.tailf.ncs.NcsMain;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;

import com.tailf.packages.ned.nedcom.NavuUtils;

/**
 * Implements various set hooks needed for
 * initializing additional config on new
 * instances.
 *
 */
@SuppressWarnings("deprecation")
public class NexusDp {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(NexusDp.class);

    private static final Pattern portChannelSwPat =
        Pattern.compile("^/ncs:devices/device\\{(\\S+)\\}/config/nx:interface/port-channel\\{([0-9]+)\\}/switchport/[^\\s]+\\s*$");

    // NOTE: only works with paths with one or more containers ending in a leaf/leaf-list
    // (path relative to interface top, call-back currenly only under switchport)
    private static final String[] autoUpdatePaths = {
        "switchport/access/vlan",
        "switchport/block/multicast",
        "switchport/block/unicast",
        "switchport/isolated",
        "switchport/mode",
        "switchport/monitor",
        "switchport/trunk/allowed/vlan/none",
        "switchport/trunk/allowed/vlan/ids",
        "switchport/trunk/native/vlan/vlan-id",
        "switchport/trunk/native/vlan/tag"
    };

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;

    // portChannelMemberUpdate
    @DataCallback(callPoint="portchannel-member-update",
                  callType=DataCBType.SET_ELEM)
    public int portChannelMemberUpdate(DpTrans trans, ConfObject[] keyPath, ConfValue value)
        throws DpCallbackException {
        return portChannelMemberUpdateInternal(trans, keyPath, DataCBType.SET_ELEM, value);
    }

    // portChannelMemberCreate
    @DataCallback(callPoint="portchannel-member-update",
                  callType=DataCBType.CREATE)
    public int portChannelMemberCreate(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        return portChannelMemberUpdateInternal(trans, keyPath, DataCBType.CREATE, null);
    }

    // portChannelMemberRemove
    @DataCallback(callPoint="portchannel-member-update",
                  callType=DataCBType.REMOVE)
    public int portChannelMemberRemove(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        return portChannelMemberUpdateInternal(trans, keyPath, DataCBType.REMOVE, null);
    }

    private String getNedSetting(int thr, String deviceId, String path) {
        String val = null;

        // Global
        String p = "/ncs:devices/ncs:global-settings/ncs:ned-settings/"+path;
        try {
            if (mm.exists(thr, p)) {
                val = ConfValue.getStringByValue(p, mm.getElem(thr, p));
            }
        } catch (Exception ignore) {
            // Ignore Exceptions
        }

        // Profile
        p = String.format("/ncs:devices/ncs:device{%s}/device-profile", deviceId);
        try {
            if (mm.exists(thr, p)) {
                String prof = ConfValue.getStringByValue(p, mm.getElem(thr, p));
                p = String.format("/ncs:devices/ncs:profiles/ncs:profile{%s}/ncs:ned-settings/%s", prof, path);
                if (mm.exists(thr, p)) {
                    val = ConfValue.getStringByValue(p, mm.getElem(thr, p));
                }
            }
        } catch (Exception ignore) {
            // Ignore Exceptions
        }

        // Device
        p = "/ncs:devices/device{"+deviceId+"}/ned-settings/"+path;
        try {
            if (mm.exists(thr, p)) {
                val = ConfValue.getStringByValue(p, mm.getElem(thr, p));
            }
        } catch (Exception ignore) {
            // Ignore Exceptions
        }

        return val;
    }

    @SuppressWarnings("deprecation")
    private int portChannelMemberUpdateInternal(DpTrans trans, ConfObject[] keyPath, DataCBType cbType, ConfValue value)
        throws DpCallbackException {
        String path = null;
        try {
            int    tid     = trans.getTransaction();
            path           = new ConfPath(keyPath).toString();

            NavuContext context = new NavuContext(mm, tid);
            Matcher swMatcher = portChannelSwPat.matcher(path);

            if (swMatcher.matches()) {
                String device_id          = swMatcher.group(1);
                String portchan_id        = swMatcher.group(2);
                String autoUpdatePath     = null;
                String leafListKey        = null;

                log.debug(String.format("portChannelMemberUpdate(%s) path: %s", cbType, path));

                String enableHook = getNedSetting(tid, device_id, "cisco-nx/transaction/enable-portchannel-set-hook");
                if ("false".equals(enableHook)) {
                    return Conf.REPLY_OK;
                }

                NavuContainer portChan = new NavuContainer(context)
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container(Ncs._config_)
                    .container("nx", "interface")
                    .list("nx", "port-channel").elem(portchan_id);

                NavuLeaf switchportEnable = portChan.container("enable").leaf("switchport");
                if (!switchportEnable.exists() || "false".equals(switchportEnable.valueAsString())) {
                    // Can only handle switchport
                    return Conf.REPLY_OK;
                }

                if (path.endsWith("}")) {
                    // quirk for leaf-list -> list in NSO >= 4.5
                    int keyStart = path.lastIndexOf('{');
                    leafListKey = path.substring(keyStart + 1,
                                                 path.length() - 1);
                    path = path.substring(0, keyStart);
                }
                for (String p : autoUpdatePaths) {
                    if (path.endsWith(p)) {
                        autoUpdatePath = p;
                    }
                }
                if (autoUpdatePath != null) {
                    NavuList ethList = new NavuContainer(context)
                        .container(Ncs.hash)
                        .container(Ncs._devices_)
                        .list(Ncs._device_)
                        .elem(new ConfKey(new ConfBuf(device_id)))
                        .container(Ncs._config_)
                        .container("nx", "interface")
                        .list("nx", "Ethernet");
                    for (NavuContainer entry : ethList.elements()) {
                        checkPortchanEntry(entry, portchan_id, cbType, value, autoUpdatePath, leafListKey);
                    }
                }
            }
        }
        catch (Exception e) {
            log.error(String.format("Error in set-hook method portChannelMemberUpdateInternal : %s", path), e);
        }
        return Conf.REPLY_OK;
    }

    private void checkPortchanEntry(NavuContainer entry, String portchan_id,
                                    DataCBType cbType, ConfValue value,
                                    String autoUpdatePath, String leafListKey)
        throws Exception
    {
        try {
            NavuContainer chanGroup = entry.container("channel-group");
            NavuLeaf chanIdLeaf = (chanGroup != null) ? chanGroup.leaf("id") : null;
            String chanId = (chanIdLeaf != null) ? chanIdLeaf.valueAsString() : null;
            if (portchan_id.equals(chanId)) {
                log.debug(String.format("found member: %s", entry.toString()));;

                NavuLeaf switchport = entry.container("enable").leaf("switchport");
                if ((cbType != DataCBType.REMOVE) && (!switchport.exists() || "false".equals(switchport.valueAsString()))) {
                    // Toggle interface to switchport
                    NavuUtils.createLeaf(switchport);
                    NavuUtils.setLeaf(switchport, "true");
                }

                NavuLeaf targetLeaf = null;
                String[] nodes = autoUpdatePath.split("/");
                NavuContainer currentNode = entry;
                for (int i = 0; i < nodes.length - 1; i++) {
                    currentNode = currentNode.container(nodes[i]);
                    if ((i > 0) && nodes[i-1].equals("allowed") && nodes[i].equals("vlan")) {
                        // OUCH: create the presence switchport/trunk/allowed/vlan
                        currentNode = NavuUtils.createContainer(currentNode);
                    }
                }
                targetLeaf = currentNode.leaf(nodes[nodes.length-1]);
                log.debug(String.format("%s %s %sin: %s", cbType, autoUpdatePath,
                                        (cbType == DataCBType.SET_ELEM) ? String.format("= %s ", value) : "",
                                        entry.toString()));
                switch (cbType) {
                case SET_ELEM:
                    NavuUtils.setLeaf(targetLeaf, value);
                    break;
                case CREATE:
                    if (leafListKey != null) {
                        navuLeafListOp(targetLeaf, "create", leafListKey);
                    } else {
                        NavuUtils.createLeaf(targetLeaf);
                    }
                    break;
                case REMOVE:
                    if (leafListKey != null) {
                        navuLeafListOp(targetLeaf, "delete", leafListKey);
                    } else {
                        targetLeaf.delete();
                    }
                    break;
                default:
                    // unhandled operation ?
                    break;
                }
            }
        } catch (NavuException e) {
            // no channel-group set?
            log.debug(String.format("Error in portChannelMember%s for %s : %s", cbType, entry.toString(), e.getMessage()));
        }
    }


    private void navuLeafListOp(NavuLeaf leafList, String operation, String value) throws Exception {
        try {
            Method method = leafList.getClass().
                getMethod(operation,
                          new Class[]{String.class});
            method.invoke(leafList, new Object[]{ value });
        } catch (InvocationTargetException e) {
            throw (Exception)e.getCause();
        } catch (NoSuchMethodException e) {
            // Bug?
        } catch (IllegalAccessException e) {
            // Bug?
        }
    }

    private final static Pattern portChannelCreatePat =
        Pattern.compile("^/ncs:devices/device\\{(\\S+)\\}/config/nx:interface/port-channel\\{([0-9]+)\\}\\s*$");

    // portChannelMemberUpdate
    @DataCallback(callPoint="init-defaults",
                  callType=DataCBType.CREATE)
    public int portChannelCreate(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        String path = null;
        try {
            int    tid     = trans.getTransaction();
            path           = new ConfPath(keyPath).toString();
            NavuContext context = new NavuContext(mm, tid);

            log.debug("portChannelCreate path (default): " + path);

            Matcher matcher = portChannelCreatePat.matcher(path);

            if (matcher.matches()) {
                String device_id = matcher.group(1);
                String portchan_id = matcher.group(2);

                ConfValue defVal = new NavuContainer(context)
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container(Ncs._config_)
                    .container("nx", "tailfned")
                    .leaf("nx", "default-lacp-suspend-individual").value();

                // In netsim we can't fetch the default
                if ((defVal != null) && !((ConfBool)defVal).booleanValue()) {
                    NavuUtils.setLeaf(new NavuContainer(context)
                                      .container(Ncs.hash)
                                      .container(Ncs._devices_)
                                      .list(Ncs._device_)
                                      .elem(new ConfKey(new ConfBuf(device_id)))
                                      .container(Ncs._config_)
                                      .container("nx", "interface")
                                      .list("nx", "port-channel")
                                      .elem(new ConfKey(new ConfBuf(portchan_id)))
                                      .container("lacp")
                                      .leaf("suspend-individual"), defVal);
                    log.debug("did set default '" + defVal.toString() + "' in lacp suspend-individual in: " + path);
                }
            }

        }
        catch (Exception e) {
            log.error(String.format("Error in set-hook method portChannelCreate : %s", path), e);
        }
        return Conf.REPLY_OK;
    }

    // NOTE: Currently not enabled in yang (not needed, see NX-520)
    private final static Pattern routeMapPathPat =
        Pattern.compile("^/ncs:devices/device\\{(\\S+)\\}/config/nx:route-map\\{(\\S+) .*$");
    @DataCallback(callPoint="route-map",
                  callType=DataCBType.REMOVE)
    public int routeMapDelete(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        String path = new ConfPath(keyPath).toString();
        log.debug("REMOVE route-map: " + path);
        int tid = trans.getTransaction();
        NavuContext context = new NavuContext(mm, tid);
        Matcher matcher = routeMapPathPat.matcher(path);
        if (matcher.matches()) {
            try {
                String device_id = matcher.group(1);
                String routeMapName = matcher.group(2);
                NavuList pbrList = new NavuContainer(context)
                    .container(Ncs.hash)
                    .container(Ncs._devices_)
                    .list(Ncs._device_)
                    .elem(new ConfKey(new ConfBuf(device_id)))
                    .container(Ncs._config_)
                    .container("nx", "pbr-stats-route-map")
                    .list("nx", "route-map");
                for (NavuContainer entry : pbrList.elements()) {
                    String pbrRMName = entry.leaf("name").valueAsString();
                    if (routeMapName.equals(pbrRMName)) {
                        log.debug(String.format("DELETE matching pbr-rm: %s", pbrRMName));
                        entry.delete();
                        break;
                    }
                }
            } catch (NavuException e) {
                // no channel-group set?
                log.debug(String.format("Error in del route-map for %s : %s", path, e.getMessage()));
            }
        }
        return Conf.REPLY_OK;
    }

    /**
     * Constructor
     *
     * @param trans - transaction handle
     * @throws DpCallbackException
     */
    @TransCallback(callType=TransCBType.INIT)
    public void NexusDpInit(DpTrans trans) throws DpCallbackException {

        try {
            if (mm == null) {
                // Need a Maapi socket so that we can attach
                Socket s = new Socket(NcsMain.getInstance().getNcsHost(),
                                      NcsMain.getInstance().getNcsPort());
                mm = new Maapi(s);
            }
            mm.attach(trans.getTransaction(),0,
                      trans.getUserInfo().getUserId());
            return;
        }
        catch (Exception e) {
            throw new DpCallbackException("Failed to attach", e);
        }
    }


    /**
     * Destructor
     *
     * @param trans - transaction handle
     * @throws DpCallbackException
     */
    @TransCallback(callType=TransCBType.FINISH)
    public void NexusDpFinish(DpTrans trans) throws DpCallbackException {

        try {
            mm.detach(trans.getTransaction());
        }
        catch (Exception e) {
            ;
        }
    }
}
