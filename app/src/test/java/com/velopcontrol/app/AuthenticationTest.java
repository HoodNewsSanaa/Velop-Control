package com.velopcontrol.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthenticationTest {
    @Test public void badPasswordIsDifferentFromLockout() throws Exception {assertTrue(Authentication.describe("ErrorAdminAccountLocked",null).contains("locked"));assertTrue(Authentication.describe("ErrorInvalidAdminPassword",new JSONObject().put("attemptsRemaining",3)).contains("Attempts remaining: 3"));}
    @Test public void delayIncludesTimeButNeverRawErrorBody() throws Exception {JSONObject o=new JSONObject().put("delayTimeRemaining",30).put("error","private-password");String s=Authentication.describe("ErrorPasswordCheckDelayed",o);assertTrue(s.contains("30 seconds"));assertFalse(s.contains("private-password"));}
    @Test public void namesContainingPasswordAreNotAutomaticallyAuthFailures(){assertFalse(Authentication.rejected("ErrorInvalidPasswordHint"));assertFalse(Authentication.rejected("ErrorUnknownAction"));assertTrue(Authentication.rejected("_ErrorUnauthorized"));}
    @Test public void authorizationDenialIsNotCalledIncorrectPassword(){assertFalse(Authentication.describe("HTTP403",null).contains("rejected the administrator credentials"));}
    @Test public void failedCheckCannotReturnSuccessfulSession() throws Exception {assertThrows(Authentication.Failure.class,()->Authentication.require(new JSONObject().put("result","ErrorAdminAccountLocked")));Authentication.require(new JSONObject().put("result","OK"));}
    @Test public void rejectedSessionStopsAllLaterCredentialedRequests(){Authentication.Guard guard=new Authentication.Guard();guard.check();guard.reject("_ErrorUnauthorized");assertThrows(Authentication.Failure.class,guard::check);guard.reject("HTTP403");try{guard.check();fail();}catch(Authentication.Failure error){assertEquals("_ErrorUnauthorized",error.code);}}
}
