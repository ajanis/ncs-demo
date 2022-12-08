package com.tailf.packages.ned.nexus;

import org.apache.http.client.methods.HttpRequestBase;

import com.tailf.ned.NedWorker;
import com.tailf.packages.ned.nedcom.NedHttpConnection;
import com.tailf.util.Base64;

public class NexusNXAPIConnection extends NedHttpConnection {

    private NexusNedCli cli;
    private String authInfo;

    NexusNXAPIConnection(NexusNedCli ned, String baseUrl) throws Exception {
        super(ned, baseUrl);
        cli = ned;
    }

    /**
     * Called after connect. Create authentication header
     */
    @Override
    protected void authenticate(NedWorker worker) {
        String auth = cli.ruser + ":" + cli.pass;
        authInfo = new String(Base64.encodeBytes(auth.getBytes()));
    }

    /**
     * Called before send of HTTP message.
     * Add authentication header to message
     */
    @Override
    protected void prepareMsg(NedWorker worker, HttpRequestBase msg) throws Exception {
        msg.addHeader("Authorization", "Basic " + authInfo);
    }

    /**
     * Called upon send error. Not used currently.
     * @param worker
     * @param errorMsg
     * @return
     */
    @Override
    protected String checkError(NedWorker worker, String errorMsg) {
        return null;
    }
}
