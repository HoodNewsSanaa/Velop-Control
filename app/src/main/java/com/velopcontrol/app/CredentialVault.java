package com.velopcontrol.app;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Remembered LAN passwords, encrypted with a non-exportable Android Keystore key. */
public final class CredentialVault {
    private static final String ALIAS="velop.credentials.v1";
    interface KeyProvider { SecretKey get() throws Exception; }
    private final SharedPreferences preferences;private final KeyProvider provider;
    public CredentialVault(Context context){this(context,null);}
    CredentialVault(Context context,KeyProvider provider){this.provider=provider;preferences=context.getSharedPreferences("credential-vault",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        if(provider!=null)return provider.get();
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    public synchronized void save(String host,String fingerprint,char[] password) throws Exception {
        byte[] plain=new String(password).getBytes(StandardCharsets.UTF_8);
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD((host+"|"+fingerprint).getBytes(StandardCharsets.UTF_8));
            String data=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(plain),Base64.NO_WRAP);
            if(!preferences.edit().putString(host,data).commit())throw new java.io.IOException("Unable to remember password");
        }finally{Arrays.fill(plain,(byte)0);}
    }
    public synchronized char[] load(String host,String fingerprint){
        String stored=preferences.getString(host,"");if(stored.isEmpty()||fingerprint.isEmpty())return new char[0];
        byte[] plain=null;
        try{String[] parts=stored.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
            cipher.updateAAD((host+"|"+fingerprint).getBytes(StandardCharsets.UTF_8));plain=cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP));return new String(plain,StandardCharsets.UTF_8).toCharArray();
        }catch(Exception e){return new char[0];}finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    public synchronized void forgetAll(){preferences.edit().clear().commit();try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(ALIAS);}catch(Exception ignored){}}
    public synchronized void forget(String host){preferences.edit().remove(host).commit();}
}
