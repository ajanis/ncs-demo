package com.tailf.packages.ned.asa;

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

import com.tailf.dp.DpCallbackException;
import com.tailf.dp.DpTrans;
import com.tailf.dp.DpUserInfo;
import com.tailf.dp.annotations.DataCallback;
import com.tailf.dp.annotations.TransCallback;
import com.tailf.dp.proto.DataCBType;
import com.tailf.dp.proto.TransCBType;

public class ASADp {

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE)
    public Maapi mm;

    private boolean isNetconf(DpTrans trans)
        throws DpCallbackException {
        DpUserInfo uinfo = trans.getUserInfo();
        return "netconf".equals(uinfo.getContext());
    }

    // contextRemove
    @DataCallback(callPoint="context-hook",
                  callType=DataCBType.REMOVE)
        public int contextRemove(DpTrans trans, ConfObject[] keyPath)
        throws DpCallbackException {
        try {
            if (isNetconf(trans)) {
                return Conf.REPLY_OK;
            }

            int tid = trans.getTransaction();
            String path = new ConfPath(keyPath).toString();
            String ctxpath = path.replace("asa:context", "asa:changeto/context");

            mm.safeDelete(tid, ctxpath);

            return Conf.REPLY_OK;
        } catch (Exception e) {
            throw new DpCallbackException("", e);
        }
    }

    // ASADpInit
    @TransCallback(callType=TransCBType.INIT)
    public void ASADpInit(DpTrans trans) throws DpCallbackException {

        try {
            if (mm == null) {
                // Need a Maapi socket so that we can attach
                String localhost = InetAddress.getLoopbackAddress().getHostAddress();
                String host = System.getProperty("host", localhost);
                Socket s = new Socket(host,NcsMain.getInstance().getNcsPort());
                mm = new Maapi(s);
            }
            mm.attach(trans.getTransaction(),0,trans.getUserInfo().getUserId());
        } catch (Exception e) {
            throw new DpCallbackException("Failed to attach", e);
        }
    }


    // ASADpFinish
    @TransCallback(callType=TransCBType.FINISH)
    public void ASADpFinish(DpTrans trans) throws DpCallbackException {
        try {
            mm.detach(trans.getTransaction());
        } catch (Exception e) {
            throw new DpCallbackException("Failed to detach", e);
        }
    }

}
