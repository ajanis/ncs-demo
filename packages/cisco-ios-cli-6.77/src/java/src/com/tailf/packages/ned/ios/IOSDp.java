package com.tailf.packages.ned.ios;

import java.net.Socket;
import java.net.InetAddress;

import com.tailf.maapi.Maapi;
import com.tailf.ncs.NcsMain;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ncs.annotations.Resource;
import com.tailf.conf.Conf;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.dp.DpCallbackException;
import com.tailf.dp.DpTrans;
import com.tailf.dp.DpUserInfo;
import com.tailf.dp.annotations.DataCallback;
import com.tailf.dp.annotations.TransCallback;
import com.tailf.dp.proto.DataCBType;
import com.tailf.dp.proto.TransCBType;

import com.tailf.navu.NavuContainer;
import com.tailf.navu.NavuContext;
import com.tailf.navu.NavuNode;
import com.tailf.navu.NavuLeaf;

import com.tailf.maapi.MaapiSchemas.CSNode;


public class IOSDp {

    private static org.apache.log4j.Logger log = org.apache.log4j.Logger.getLogger(IOSDp.class);

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;

    private boolean isNetconf(DpTrans trans)
        throws DpCallbackException {

        DpUserInfo uinfo = trans.getUserInfo();
        return "netconf".equals(uinfo.getContext());
    }

    /*
     * navuNodeModified
     */
    private int navuNodeModified(NavuNode top) throws Exception {
        int num = 0;
        for (NavuNode child : top.children()) {
            CSNode node = child.getInfo().getCSNode();
            if (child.getInfo().isContainer()) {
                NavuContainer container = (NavuContainer)child;
                boolean isPresence = node.getMinOccurs() == 0;
                if (isPresence && container.exists()) {
                    num = num + 1;
                }
                num += navuNodeModified(child);
            } else if (child.getInfo().isLeaf()) {
                NavuLeaf leaf = (NavuLeaf)child;
                if (leaf.exists()) {
                    num = num + 1;
                }
            } else if (child.getInfo().isList()) {
                for (NavuNode grandchild : child.children()) {
                    num += navuNodeModified(grandchild);
                }
            }
        }
        return num;
    }


    /*
     * deleteWhenEmptyHook
     * WARNING: Not working if commit-dry is performed first. Reported in TRAC 15879.
     */
    @DataCallback(callPoint="delete-when-empty-patch",
            callType=DataCBType.REMOVE)
        public int deleteWhenEmptyHook(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            int th = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String root = path.substring(0, 1 + path.lastIndexOf('}'));

            if (isNetconf(trans)) {
                return Conf.REPLY_OK;
            }

            // Deleting root, no check needed
            if (path.equals(root)) {
                return Conf.REPLY_OK;
            }

            // Entry already deleted (in this hook) in same transaction
            if (!mm.exists(th, root)) {
                return Conf.REPLY_OK;
            }

            // Check NED setting
            String deviceId = path.replaceFirst(".*/device\\{(\\S+)\\}/config.*", "$1");
            if (!getNedSettingBoolean(th,deviceId,"cisco-ios/auto/delete-when-empty-patch",false)) {
                return Conf.REPLY_OK;
            }

            // Scan entry for creates leaves or presence containers
            NavuContext context = null;
            try {
                context = new NavuContext(mm, th);
                NavuNode node = (NavuNode)new NavuContainer(context).getNavuNode(new ConfPath(root));
                int num = navuNodeModified(node) - 1;
                if (num == 0) {
                    mm.safeDelete(th, root);
                }
            } finally {
                if (context != null) {
                    context.removeCdbSessions();
                }
            }
        } catch (Exception ignore) {
            // Ignore exception
        }
        return Conf.REPLY_OK;
    }


    // interfaceSwitchportCreate
    @DataCallback(callPoint="interface-switchport-hook",
            callType=DataCBType.CREATE)
        public int interfaceSwitchportCreate(DpTrans trans, ConfObject[] keyPath)
            throws DpCallbackException {
        try {
            if (isNetconf(trans)) {
                return Conf.REPLY_OK;
            }

            int th = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String ifpath = path.replace("switchport", "");

            // Delete primary and secondary IP address(es)
            mm.safeDelete(th, ifpath+"ip/address");

            // Check device version, act on device type
            ConfValue val = getPlatformModel(th, path);
            if (val == null) {
                String toppath = path.substring(0, path.indexOf("interface"));
                if (mm.exists(th, toppath+"cached-show/version/model")) {
                    val = mm.safeGetElem(th, toppath+"cached-show/version/model");
                }
            }
            String model = val != null ? val.toString() : "*unknown*";
            log.debug("interface-switchport-hook: model="+model);

            if (model.contains("C891")) {
                // Don't delete 'no ip address' since can be set on these device(s)
                return Conf.REPLY_OK;
            }

            // Clear 'no ip address' to avoid diff due to NCS bug with default values in choice
            log.debug("interface-switchport-hook: deleted "+ifpath+"ip/no-address/address");
            mm.safeDelete(th, ifpath+"ip/no-address/address");
            return Conf.REPLY_OK;

        } catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    private ConfValue getPlatformModel(int th, String path) {
        try {
            String modelpath = path.substring(0, path.indexOf("/config/")+1) + "platform/model";
            if (mm.exists(th, modelpath)) {
                return mm.safeGetElem(th, modelpath);
            }
        } catch (Exception ignore) {
            // Ignore, devices device platform does not exist in All NCS/NSO versions
        }
        return null;
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
        p = "/ncs:devices/ncs:profiles/profile{cisco-ios}/ncs:ned-settings/"+path;
        try {
            if (mm.exists(thr, p)) {
                val = ConfValue.getStringByValue(p, mm.getElem(thr, p));
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

    private boolean getNedSettingBoolean(int thr, String deviceId, String path, boolean defaultValue) {
        boolean value = defaultValue;
        String setting = getNedSetting(thr, deviceId, path);
        if (setting != null) {
            value = "true".equals(setting) ? true : false;
        }
        return value;
    }


    // iosDpInit
    @TransCallback(callType=TransCBType.INIT)
    public void iosDpInit(DpTrans trans) throws DpCallbackException {

        try {
            if (mm == null) {
                // Need a Maapi socket so that we can attach
                String localhost = InetAddress.getLoopbackAddress().getHostAddress();
                String host = System.getProperty("host", localhost);
                Socket s = new Socket(host,NcsMain.getInstance().getNcsPort());
                mm = new Maapi(s);
            }
            int th = trans.getTransaction();
            mm.attach(th, 0, trans.getUserInfo().getUserId());
        } catch (Exception e) {
            throw new DpCallbackException("Failed to attach", e);
        }
    }


    // iosDpFinish
    @TransCallback(callType=TransCBType.FINISH)
    public void iosDpFinish(DpTrans trans) throws DpCallbackException {
        try {
            mm.detach(trans.getTransaction());
        } catch (Exception ignore) {
            // Ignore exception
        }
    }

}
