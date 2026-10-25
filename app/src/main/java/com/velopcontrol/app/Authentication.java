package com.velopcontrol.app;

import org.json.*;

/** Exact protocol statuses rather than substring-based password guesses. */
public final class Authentication {
    public static final String CHECK="core/CheckAdminPassword3",STATUS="core/GetAdminPasswordAuthStatus";
    /** A rejected session cannot consume more password attempts through refresh or another tool. */
    public static final class Guard {
        private String rejection;
        public synchronized void check() {
            if(rejection!=null)throw new Failure(rejection,null);
        }
        public synchronized void reject(String code){if(rejection==null)rejection=code;}
    }
    public static final class Failure extends SecurityException {
        public final String code;
        public Failure(String code,JSONObject output){super(describe(code,output));this.code=SettingsRepository.safeCode(code);}
    }
    public static boolean rejected(String code){return code.equals("_ErrorUnauthorized")||code.equals("ErrorUnauthorized")||code.equals("ErrorInvalidAdminPassword")||code.equals("ErrorAdminAccountLocked")||code.equals("ErrorPasswordCheckDelayed");}
    public static void require(JSONObject response){String code=response.optString("result","MissingResult");if(rejected(code))throw new Failure(code,response.optJSONObject("output"));SettingsRepository.requireOK(response);}
    public static String describe(String code,JSONObject output){
        int seconds=output==null?0:Math.max(0,Math.min(86400,output.optInt("delayTimeRemaining")));
        int attempts=output==null?-1:output.optInt("attemptsRemaining",-1);
        if(code.equals("ErrorAdminAccountLocked"))return "This node’s administrator account is locked. Password recovery is required; no automatic retry was made.";
        if(code.equals("ErrorPasswordCheckDelayed"))return "This node has delayed password checks after previous failures."+(seconds>0?" Wait at least "+seconds+" seconds.":"")+" No automatic retry was made.";
        if(code.equals("HTTP403"))return "This node denied access (HTTP 403). This does not establish whether the password is wrong.";
        return "This node rejected the administrator credentials ("+SettingsRepository.safeCode(code)+")."+(attempts>=0&&attempts<=100?" Attempts remaining: "+attempts+".":"")+" No automatic retry was made. Reconnect this node before making more authenticated requests.";
    }
    private Authentication(){}
}
