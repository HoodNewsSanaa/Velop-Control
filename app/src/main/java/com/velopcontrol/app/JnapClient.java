package com.velopcontrol.app;

import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.*;

/** Pinned local HTTPS transport. No redirects, logging of bodies, or automatic retries. */
public final class JnapClient implements AutoCloseable, SettingsRepository.Transport {
    private final String host;
    private final byte[] pin;
    private char[] password;
    private final SSLSocketFactory tls;
    private final Authentication.Guard authGuard=new Authentication.Guard();
    private volatile boolean modernPasswords;

    public static final class Identity {
        public final byte[] fingerprint;
        public final String subject;
        Identity(X509Certificate certificate) throws Exception {
            fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            subject = certificate.getSubjectX500Principal().getName();
        }
        public String formatted() {
            StringBuilder b = new StringBuilder();
            for (byte v : fingerprint) { if (b.length() > 0) b.append(':'); b.append(String.format("%02X", v & 255)); }
            return b.toString();
        }
    }

    // This connection retrieves only the certificate. It never sends HTTP or credentials.
    public static Identity inspect(String address) throws Exception {
        String host = ReadPolicy.privateAddress(address);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust(null)}, null);
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, 443), 5000);
            try (SSLSocket secure = (SSLSocket) context.getSocketFactory().createSocket(socket, host, 443, true)) {
                secure.setSoTimeout(5000);
                secure.startHandshake();
                return new Identity((X509Certificate) secure.getSession().getPeerCertificates()[0]);
            }
        }
    }

    @android.annotation.SuppressLint("CustomX509TrustManager") // Self-signed LAN device: explicit first trust, then exact SHA-256 pin. No credentials in inspection.
    private static X509TrustManager trust(byte[] expected) {
        return new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Client certificates are unsupported");
            }
            public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                if (chain.length == 0) throw new java.security.cert.CertificateException("Missing certificate");
                if (expected != null) {
                    try {
                        byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        if (!MessageDigest.isEqual(expected, actual)) throw new java.security.cert.CertificateException("Router certificate changed");
                    } catch (java.security.NoSuchAlgorithmException e) { throw new java.security.cert.CertificateException(e); }
                }
            }
        };
    }

    public JnapClient(String address, char[] password, byte[] fingerprint) throws Exception {
        host = ReadPolicy.privateAddress(address);
        if (fingerprint == null || fingerprint.length != 32) throw new IllegalArgumentException("Approve the router certificate first");
        pin = fingerprint.clone();
        this.password = password.clone();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trust(pin)}, null);
        tls = context.getSocketFactory();
    }

    public JSONObject read(String action, boolean authenticated) throws Exception {
        if(!ReadPolicy.ACTIONS.contains(action)&&!SettingsCatalog.allowsRead(action))throw new IllegalArgumentException("Read action blocked");
        return request(action,new JSONObject(),authenticated);
    }
    public JSONObject write(String action,JSONObject body) throws Exception {
        if(!SettingsCatalog.allowsWrite(action))throw new IllegalArgumentException("Write action blocked");
        return request(action,body,true);
    }
    public String host(){return host;}
    synchronized char[] passwordCopy(){if(password==null)throw new IllegalStateException("Disconnected");return password.clone();}
    public JSONObject authenticate(JSONObject identity) throws Exception {
        boolean modern=false;org.json.JSONArray services=identity.optJSONArray("services");
        for(int i=0;services!=null&&i<services.length();i++)if(services.optString(i).equals("http://linksys.com/jnap/core/Core7"))modern=true;
        modernPasswords=modern;
        if(!modern){JSONObject reply=read("core/CheckAdminPassword",true);Authentication.require(reply);return reply;}
        JSONObject state=read(Authentication.STATUS,false);
        if("OK".equals(state.optString("result"))){JSONObject o=state.optJSONObject("output");
            if(o!=null&&o.optInt("attemptsRemaining",1)==0)throw new Authentication.Failure("ErrorAdminAccountLocked",o);
            if(o!=null&&o.optInt("delayTimeRemaining")>0)throw new Authentication.Failure("ErrorPasswordCheckDelayed",o);
        }
        char[] secret=passwordCopy();
        try{JSONObject reply=request(Authentication.CHECK,new JSONObject().put("adminPassword",new String(secret)),false);Authentication.require(reply);return reply;}
        finally{Arrays.fill(secret,'\0');}
    }
    public synchronized void updatePassword(char[] next){if(password!=null)Arrays.fill(password,'\0');password=next.clone();}
    public boolean modernPasswords(){return modernPasswords;}
    /** Explicit diagnostic GET, using the same approved LAN TLS pin and no redirects. */
    public WiredNeighbors readWiredNeighbors() throws Exception {
        authGuard.check();
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://"+host+"/sysinfo.cgi?section=fwinfo").openConnection();
        connection.setSSLSocketFactory(tls);connection.setHostnameVerifier((hostname,session)->hostname.equals(host));
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(20000);connection.setUseCaches(false);
        char[] secret=passwordCopy();byte[] credentials=null;
        try{
            credentials=("admin:"+new String(secret)).getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Authorization","Basic "+Base64.encodeToString(credentials,Base64.NO_WRAP));
            int status=connection.getResponseCode();
            if(status==401||status==403){String code="HTTP"+status;authGuard.reject(code);throw new Authentication.Failure(code,null);}
            if(status!=200)throw new java.io.IOException("System-information page was unavailable");
            try(InputStream input=new java.io.BufferedInputStream(connection.getInputStream())){return WiredNeighbors.parse(input);}
        }finally{Arrays.fill(secret,'\0');if(credentials!=null)Arrays.fill(credentials,(byte)0);connection.disconnect();}
    }
    private JSONObject request(String action,JSONObject body,boolean authenticated) throws Exception {
        if(authenticated)authGuard.check();
        final byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://" + host + "/JNAP/").openConnection();
        connection.setSSLSocketFactory(tls);
        // The exact approved certificate replaces public hostname/CA validation for this session.
        connection.setHostnameVerifier((hostname, session) -> hostname.equals(host));
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(8000);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("X-JNAP-Action", "http://linksys.com/jnap/" + action);
        if (authenticated) {
            byte[] credentials;
            synchronized (this) {
                if (password == null) throw new IllegalStateException("Disconnected");
                credentials = ("admin:" + new String(password)).getBytes(StandardCharsets.UTF_8);
            }
            connection.setRequestProperty("X-JNAP-Authorization", "Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP));
            Arrays.fill(credentials, (byte) 0);
        }
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(payload); }
            int status = connection.getResponseCode();
            if(status==401){if(authenticated)authGuard.reject("HTTP401");throw new Authentication.Failure("HTTP401",null);}
            if(status==403){if(authenticated)authGuard.reject("HTTP403");throw new Authentication.Failure("HTTP403",null);}
            if (status != 200) throw new java.io.IOException("Router HTTP status " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new java.io.IOException("Router response exceeds 2 MiB");
                    output.write(buffer, 0, count);
                }
                JSONObject response = new JSONObject(output.toString("UTF-8"));
                response.remove("error");
                String result = response.optString("result");
                boolean passwordPolicyError=result.equals("ErrorInvalidAdminPassword")&&action.startsWith("core/SetAdminPassword");
                if(Authentication.rejected(result)&&!passwordPolicyError){
                    if(authenticated||action.equals(Authentication.CHECK))authGuard.reject(result);
                    throw new Authentication.Failure(result,response.optJSONObject("output"));
                }
                return response;
            }
        } finally { Arrays.fill(payload,(byte)0);connection.disconnect(); }
    }

    public synchronized void close() { if (password != null) Arrays.fill(password, '\0'); password = null; }
}
