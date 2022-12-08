%    -*- Erlang -*-
%    Author:    Johan Bevemyr
%    Author:    Johan Nordlander

-module(client_pnp).
-author('jb@mail.tail-f.com').

-on_load(on_load/0).

-export([start/0]).

-define(i2l(X), integer_to_list(X)).
-define(l2i(X), list_to_integer(X)).
-define(l2b(X), list_to_binary(X)).
-define(l2f(X), list_to_float(X)).
-define(b2l(X), binary_to_list(X)).
-define(a2l(X), atom_to_list(X)).
-define(l2a(X), list_to_atom(X)).

-record(state, {serial                        :: string(),
                base_url                      :: string(),
                correlator = 0                :: integer(),
                sudi_serial = undefined       :: undefined | string(),
                sudi_key_file = undefined     :: undefined | string(),
                sudi_key_password = undefined :: undefined | string(),
                sudi_cert_file = undefined    :: undefined | string(),
                sid = undefined               :: undefined | string(),
                socket_options = []           :: [{atom(), string()}]
               }).

on_load() ->
    proc_lib:spawn(fun start/0),
    ok.

start() ->
    case env_var("NETSIM_PNP_SERIAL") of
        undefined ->
            no_pnp;
        Serial    ->
            pnp_hello(
              #state{serial            = Serial,
                     base_url          = env_var("NETSIM_PNP_URL",
                                                 "http://pnpserver"),
                     sudi_serial       = env_var("SUDI_SERIAL"),
                     sudi_key_file     = env_var("SUDI_KEY_FILE"),
                     sudi_key_password = env_var("SUDI_KEY_PASSWORD"),
                     sudi_cert_file    = env_var("SUDI_CERT_FILE"),
                     socket_options    = socket_options()})
    end.

socket_options() ->
    lists:flatten([env_var_option(cacertfile, "CACERT_FILE"),
                   env_var_option(certfile, "CERT_FILE"),
                   env_var_option(keyfile, "KEY_FILE"),
                   env_var_option(password, "KEY_PASSWORD")]).

env_var_option(Key, EnvVar) ->
    case os:getenv(EnvVar) of
        false -> [];
        Value -> [{Key, Value}]
    end.

env_var(EnvVar) ->
    env_var(EnvVar, undefined).

env_var(EnvVar, DefaultValue) ->
    case os:getenv(EnvVar) of
        false -> DefaultValue;
        Value -> Value
    end.

%% this loop should first do hello, then work request, and fake
%% device info and cli-cmd execution, and respect backoff
pnp_hello(State) ->
    Xml = {info, [], ""},
    Res = do_post(State, "HELLO", Xml),
    if Res == error ->
            timer:sleep(30*1000),
            pnp_hello(State);
       true ->
            pnp_work_request(State#state{correlator = 1})
    end.

pnp_work_request(State) ->
    %% error_logger:format("Work request\n", []),
    Xml = build_work_request(State),
    Res = do_post(State, "WORK-REQUEST", Xml),
    IsDevInfo = find_tag(deviceInfo, Res),
    IsConfigApply = find_tag(configApply, Res),
    ApplyTo = find_tag(applyTo, Res),
    IsBackoff = find_tag(backoff, Res),
    CertificateInstall = find_tag(certificateInstall, Res),
    ConfigUpgrade = find_tag(config, Res),
    Reload = find_tag(reload, Res),
    DeviceAuth = find_tag(deviceAuth, Res),
    error_logger:format("** Result: ~p\n", [Res]),
    if IsDevInfo =/= false ->
            pnp_work_response('deviceInfo', Res, State);
       IsConfigApply =/= false ->
            Cfg = find_tag('cli-config-data-block', Res),
            apply_cli_config(Cfg),
            pnp_work_response('configApply', Res, State);
       ApplyTo =/= false ->
            pnp_work_response('applyTo', Res, State);
       CertificateInstall =/= false ->
           UpdatedState = download_certificate(Res, State),
           pnp_work_response('certificateInstall', Res, UpdatedState);
       ConfigUpgrade =/= false ->
           pnp_work_response('configUpgrade', Res, State);
       Reload =/= false ->
           pnp_work_response('reload', Res, State);
       DeviceAuth =/= false ->
           pnp_work_response('deviceAuth', Res, State);
       IsBackoff =/= false ->
            pnp_delay(get_delay(Res,60), State);
       Res == error ->
            pnp_delay(60, State);
       true ->
            pnp_error(Res, State)
    end.

apply_cli_config(false) ->
    ok;
apply_cli_config({_,_,[Cfg]}) ->
    try
        %% error_logger:format("Cfg=~p\n", [Cfg]),
        {ok, File} = misc:mktemp("cfg", file),
        file:write_file(File, "enable\nconfig terminal\n"++Cfg),
        {ip, [{_,Port}|_]} = confd_ia:get_address(),
        Res = os:cmd("cat "++File++" | confd_cli -I -n -u admin -P "++?i2l(Port)),
        %% error_logger:format("Res=~p\n", [Res]),
        ok
    catch
        X:Y ->
            StackTrace = erlang:get_stacktrace(),
            error_logger:format("apply_cli_config failed: ~p:~p\n~p\n",
                                [X,Y, StackTrace])
    end.

download_certificate(Res, State) ->
    CaCertFilePath = "/tmp/certificate_install.ca.cert",
    {uri, _, [Uri]} = find_tag(uri, Res),
    {ok, Data} = do_get(State, Uri),
    ok = file:write_file(CaCertFilePath, Data),
    SocketOptions0 = State#state.socket_options,
    SocketOptions = lists:keystore(cacertfile,
                                   1,
                                   SocketOptions0,
                                   {cacertfile, CaCertFilePath}),
    Host = get_host(Res),
    Port = case find_tag(port, Res) of
               false              -> "";
               {port, _, [Value]} -> Value
           end,
    BaseUrl = lists:flatten(io_lib:format("https://~s:~s", [Host, Port])),
    State#state{base_url = BaseUrl, socket_options = SocketOptions}.

get_host(Res) ->
    get_host(Res, [ipv4, ipv6, host]).

get_host(_, []) ->
    error("Missing host in certificate install work request");
get_host(Res, [H | T]) ->
    case find_tag(H, Res) of
        false           -> get_host(Res, T);
        {H, _, [Value]} -> Value
    end.

do_get(State, Url) ->
    SocketOptions = State#state.socket_options,
    case purl:get(Url, SocketOptions) of
        {200, _Headers, Body} ->
            {ok, xml_parser:parse_xml(lists:concat(Body))};
        {error, Error}         ->
            error_logger:format("pnp client got error on ~s: ~p\n",
                                [Url, Error]),
            error
    end.

pnp_work_response(Req, Res, State0) ->
    Xml = build_work_response(Req, Res, State0),
    WorkRes = do_post(State0, "WORK-RESPONSE", Xml),
    error_logger:format("** WORKResult: ~p\n", [WorkRes]),
    IsByeBye = find_tag(bye, WorkRes),
    IsBackoff = find_tag(backoff, WorkRes),
    State = update_sid(State0, WorkRes),
    if IsByeBye =/= false ->
            pnp_delay(2, State);
       IsBackoff =/= false ->
            pnp_delay(get_delay(WorkRes, 60), State);
       Res == error ->
            pnp_delay(60, State);
       true ->
            pnp_error(WorkRes, State)
    end.

update_sid(State, WorkResponse) ->
    case find_tag(sid, WorkResponse) of
        false         -> State;
        {sid, _, Sid} -> State#state{sid = Sid}
    end.

pnp_error(Res, State) ->
    Xml = build_work_response(error, Res, State),
    _Res = do_post(State, "WORK-RESPONSE", Xml),
    pnp_delay(60, State).

pnp_delay(never, _State) ->
    pnp_done;
pnp_delay(Delay, State) ->
    % error_logger:format("Pnp delay ~w\n", [Delay]),
    timer:sleep(Delay*1000),
    pnp_work_request(State#state{correlator = State#state.correlator + 1}).


do_post(State, Cmd, Xml) ->
    Url = State#state.base_url ++ "/pnp/" ++ Cmd,
    Payload = lists:flatten(yaws_api:ehtml_expand(Xml)),
    SocketOptions = State#state.socket_options,
    ContentType = "text/xml; charset=utf-8",
    error_logger:format("** Payload: ~p\n", [Payload]),
    case purl:post(Url, Payload, ContentType, SocketOptions) of
        {200, _Headers, Body} ->
            xml_parser:parse_xml(lists:concat(Body));
        {error, Error}        ->
            error_logger:format("pnp client got error on ~s: ~p\n",
                                [Cmd, Error]),
			error
    end.

build_work_request(State) ->
    Sudi = case State#state.sudi_serial of
               undefined  -> [];
               SudiSerial -> [{'SUDI', [], [SudiSerial]}]
           end,
    {pnp,[{xmlns,"urn:cisco:pnp"},
          {version,"1.0"},
          {udi, serial2udi(State#state.serial)} |
          sid(State)],
     [{info,[{xmlns,"urn:cisco:pnp:work-info"},
             {correlator,udi_corr(State#state.correlator)}],
       [{deviceid,[],
         [{udi,[],[serial2udi(State#state.serial)]},
          {authrequired,[],["false"]}]} | Sudi]}]}.


build_work_response(deviceInfo, _Res, State) ->
    {_, Port} = confd_cfg:get([port, ssh, cli, confdConfig]),
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi, serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{correlator,udi_corr(State#state.correlator)},
        {success,"1"},
        {xmlns,"urn:cisco:pnp:device-info"}],
       [{udi,[],
         [{'primary-chassis',[],[serial2udi(State#state.serial)]}]},
        {imageinfo,[],
         [{versionstring,[],
           ["Cisco IOS Software, C2900 Software (C2900-UNIVERSALK9-M), "
            "Version 15.4(3)M, RELEASE SOFTWARE (fc1)\nTechnical Support: "
            "http://www.cisco.com/techsupport\nCopyright (c) 1986-2014 by "
            "Cisco Systems, Inc.\nCompiled Mon 21-Jul-14 19:29 by "
            "prod_rel_team"]},
          {imagefile,[],["flash0:c2900-universalk9-mz.SPA.154-3.M.bin"]},
          {imagehash,[],[]},
          {returntoromreason,[],["power-on"]},
          {bootvariable,[],[]},
          {bootldrvariable,[],[]},
          {configvariable,[],[]},
          {configreg,[],["0x2102"]},
          {configregnext,[],[]}]},
        {hardwareinfo,[],
         [{hostname,[],["Router"]},
          {vendor,[],["Cisco"]},
          {platformname,[],["CISCO2901/K9"]},
          {processortype,[],[]},
          {hwrevision,[],["1.0"]},
          {mainmemsize,[],["-1728053248"]},
          {iomemsize,[],["117440512"]},
          {boardid,[],[State#state.serial]},
          {boardreworkid,[],[]},
          {processorrev,[],[]},
          {midplaneversion,[],[]},
          {location,[],[]}]},
        {filesystemlist,[],[{filesystem,[],[]},{filesystem,[],[]}]},
	{profileInfo, [], [{profile,[{'profile-name',"pnp-test"},
        {'discovery-created',env_var("PNP_DISCOVERY_CREATED")}],[]}]},
        {'netsim-port', [], [?i2l(Port)]}]}]};
build_work_response(configApply, _Res, State) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{xmlns,"urn:cisco:pnp:cli-config"},
        {correlator,udi_corr(State#state.correlator)},
        {success,"1"}],
       [{resultentry,
         [{linenumber,"1"},
          {clistring,"username admin privilege 15 password 0 admin\r"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"2"},{clistring,"hostname test"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"3"},{clistring,"ip domain-name tail-f.com"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"4"},
          {clistring,"crypto key generate rsa modulus 1024"}],
         [{success,[],[]},
          {text,[],
           ["\n**CLI Line # 4: The name for the keys will be: "
            "test.tail-f.com\r\n**CLI Line # 4: % The key modulus size is "
            "1024 bits\r\n**CLI Line # 4: % Generating 1024 bit RSA keys, "
            "keys will be non-exportable...\r\n**CLI Line # 4: [OK] "
            "(elapsed time was 2 seconds)\r"]}]},
        {resultentry,
         [{linenumber,"5"},{clistring,"username admin password admin"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"6"},{clistring,"enable secret secret"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"7"},{clistring,"ip ssh version 2"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"8"},{clistring,"line vty 0 4"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"9"},{clistring,"transport input ssh"}],
         [{success,[],[]}]},
        {resultentry,
         [{linenumber,"10"},{clistring,"login local"}],
         [{success,[],[]}]}]}]};
build_work_response(applyTo, _Res, State) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{xmlns,"urn:cisco:pnp:config-upgrade"},
        {correlator,udi_corr(State#state.correlator)},
        {success,"1"}], []}]};
build_work_response(certificateInstall, _Res, State) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{xmlns,"urn:cisco:pnp:certificate-install"},
        {correlator,udi_corr(State#state.correlator)},
        {success,"1"}], []}]};
build_work_response(configUpgrade, _Res, State) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{xmlns,"urn:cisco:pnp:config-upgrade"},
        {correlator,udi_corr(State#state.correlator)},
        {success,"1"}], []}]};
build_work_response(reload, _Res, State) ->
     {pnp,
      [{xmlns,"urn:cisco:pnp"},
       {version,"1.0"},
       {udi,serial2udi(State#state.serial)} | sid(State)],
      [{response,
        [{xmlns,"urn:cisco:pnp:reload"},
         {correlator,udi_corr(State#state.correlator)},
         {success,"1"}], []}]};
build_work_response(deviceAuth, Res, State) ->
    ChallengeRequest = find_tag('challenge-request', Res),
    HashMethod = find_tag('hash-method', Res),

    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{xmlns,"urn:cisco:pnp:device-auth"},
        {correlator,udi_corr(State#state.correlator)},
        {success,"1"}],
       [{'challenge-response', [],
         [sudi_challenge(State, ChallengeRequest, HashMethod)]},
        {'sudi-cert', [], [sudi_cert(State)]}]}]};
build_work_response(error, _Res, State) ->
    {pnp,
     [{xmlns,"urn:cisco:pnp"},
      {version,"1.0"},
      {udi,serial2udi(State#state.serial)} | sid(State)],
     [{response,
       [{xmlns,"urn:cisco:pnp:fault"}],
       [{fault,[],
         [{faultcode,[],["XSVC:Client"]},
          {faultstring,[],["An unknown XML tag has been received"]},
          {detail,[],
           [{'xsvc-err:error',
             [{'xmlns:xsvc-err',"urn:cisco:pnp:error"}],
             [{'xsvc-err:details',[],["struct"]}]}]}]}]}]}.

sid(State) ->
    case State#state.sid of
        undefined -> [];
        Sid       -> [{sid, Sid}]
    end.

sudi_challenge(State, {_, _, [ChallengeRequest]}, {_, _, [DigestType0]}) ->
    case {State#state.sudi_key_file, State#state.sudi_key_password} of
        {KeyFile, _} when KeyFile =:= ""; KeyFile =:= undefined ->
            "Invalid/missing key file specified (SUDI_KEY_FILE)";
        {_, Password} when Password =:= ""; Password =:= undefined ->
            "Invalid/missing password for key specified (SUDI_KEY_PASSWORD)";
        {KeyFile, Password} ->
            {ok, PemBin} = file:read_file(KeyFile),
            [RSAEntry] = public_key:pem_decode(PemBin),
            Msg = erlang:list_to_binary(ChallengeRequest),
            DigestType = erlang:list_to_atom(string:to_lower(DigestType0)),
            Key = public_key:pem_entry_decode(RSAEntry, Password),
            Signed = public_key:sign(Msg, DigestType, Key),
            erlang:binary_to_list(base64:encode(Signed))
    end;
sudi_challenge(_, _, _) ->
    "Missing challenge-request or hash-method in request".

sudi_cert(State) ->
    case State#state.sudi_cert_file of
        CertFile when CertFile =:= ""; CertFile =:= undefined ->
            "Invalid/missing cert file specified (SUDI_CERT_FILE)";
        CertFile ->
            {ok, PemBin} = file:read_file(CertFile),
            erlang:binary_to_list(base64:encode(PemBin))
    end.

udi_corr(Correlator) ->
    "udi"++?i2l(Correlator).

serial2udi(Serial) ->
    "PID:CISCO2901/K9,VID:V06,SN:"++Serial.

get_delay(Xml, Default) ->
	case find_tag(callbackAfter, Xml) of
	    {_, _, Tags} ->
            try
                Hours = ?l2i(get_val(hours, Tags, "0")),
                Mins = ?l2i(get_val(minutes, Tags, "0")),
                Secs = ?l2i(get_val(seconds, Tags, "0")),
                V = (Hours*60 + Mins)*60 + Secs,
                if V > 0 -> V; true -> Default end
            catch _ ->
                Default
            end;
        false ->
			never
    end.

find_tag(_Tag, []) ->
    false;
find_tag(Tag, [T={Tag, _Attr, _Body}|_]) ->
    T;
find_tag(Tag, [T={Tag, _Attr}|_]) ->
    T;
find_tag(Tag, [{_Tag, _Attr}|Rest]) ->
    find_tag(Tag, Rest);
find_tag(Tag, [{_Tag, _Attr, Body}|Rest]) ->
    case find_tag(Tag, Body) of
        false ->
            find_tag(Tag, Rest);
        Elem ->
            Elem
    end;
find_tag(_Tag, _) ->
    false.

get_val(Key, L, Default) ->
    case lists:keysearch(Key, 1, L) of
        {value, {_, undefined}} -> Default;
        {value, {_, Val}} -> Val;
        {value, {_, _, [Val]}} -> Val;
        _ -> Default
    end.

