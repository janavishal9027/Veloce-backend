package com.veloce.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CredentialVault {
    private final byte[] key;private final SecureRandom random=new SecureRandom();
    CredentialVault(@Value("${veloce.study-key:${veloce.jwt-secret}}") String secret) {
        try{key=MessageDigest.getInstance("SHA-256").digest(("Veloce calendar credentials\u0000"+secret).getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public String seal(String value) {
        try{byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }catch(GeneralSecurityException e){throw new IllegalStateException("Credential encryption failed.");}
    }
    public String open(String value) {
        try{var parts=value.split("\\.");var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        }catch(GeneralSecurityException|IllegalArgumentException|ArrayIndexOutOfBoundsException e){
            throw new Problem(412,"CALENDAR_RECONNECT","Reconnect Google Calendar after the server credential key changed.");}
    }
}
