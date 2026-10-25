package com.velopcontrol.app;
import android.content.Context;
import javax.crypto.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
public class CredentialVaultTest {
    private Context context;private SecretKey key;
    @Before public void setup() throws Exception {context=RuntimeEnvironment.getApplication();context.getSharedPreferences("credential-vault",0).edit().clear().commit();KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);key=g.generateKey();}
    @Test public void passwordSurvivesNewVaultInstanceButIsNotStoredAsPlaintext() throws Exception {
        String secret="Test-Only-é-password-123";CredentialVault first=new CredentialVault(context,()->key);first.save("192.168.1.1","fingerprint",secret.toCharArray());
        CredentialVault again=new CredentialVault(context,()->key);assertArrayEquals(secret.toCharArray(),again.load("192.168.1.1","fingerprint"));
        for(Object stored:context.getSharedPreferences("credential-vault",0).getAll().values())assertFalse(String.valueOf(stored).contains(secret));
    }
    @Test public void hostAndCertificateAreBoundAndNodesAreIsolated() throws Exception {
        CredentialVault vault=new CredentialVault(context,()->key);vault.save("192.168.1.1","one","primary-pass".toCharArray());vault.save("192.168.1.2","two","child-pass".toCharArray());
        assertArrayEquals("primary-pass".toCharArray(),vault.load("192.168.1.1","one"));assertArrayEquals("child-pass".toCharArray(),vault.load("192.168.1.2","two"));assertEquals(0,vault.load("192.168.1.1","changed").length);assertEquals(0,vault.load("192.168.1.3","one").length);
    }
    @Test public void forgettingCredentialsRemovesPersistedPasswords() throws Exception {
        CredentialVault vault=new CredentialVault(context,()->key);vault.save("192.168.1.1","pin","temporary-password".toCharArray());vault.forgetAll();assertEquals(0,new CredentialVault(context,()->key).load("192.168.1.1","pin").length);
    }
    @Test public void forgettingOneNodeKeepsOtherNodeCredentials()throws Exception{CredentialVault vault=new CredentialVault(context,()->key);vault.save("192.168.1.1","one","primary-pass".toCharArray());vault.save("192.168.1.2","two","child-pass".toCharArray());vault.forget("192.168.1.2");assertEquals(0,vault.load("192.168.1.2","two").length);assertArrayEquals("primary-pass".toCharArray(),vault.load("192.168.1.1","one"));}
}
