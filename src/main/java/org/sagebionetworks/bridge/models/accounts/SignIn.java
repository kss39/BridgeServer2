package org.sagebionetworks.bridge.models.accounts;

import org.sagebionetworks.bridge.models.BridgeEntity;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(builder = SignIn.Builder.class)
public final class SignIn implements BridgeEntity {

    private final String email;
    private final Phone phone;
    private final String externalId;
    private final String password;
    private final String appId;
    private final String token;
    private final String reauthToken;
    
    private SignIn(String appId, String email, Phone phone, String externalId, String password, String token,
            String reauthToken) {
        this.appId = appId;
        this.email = email;
        this.phone = phone;
        this.externalId = externalId;
        this.password = password;
        this.token = token;
        this.reauthToken = reauthToken;
    }
    
    public String getAppId() {
        return appId;
    }
    
    public String getEmail() {
        return email;
    }
    
    public Phone getPhone() {
        return phone;
    }

    public String getExternalId() {
        return externalId;
    }
    
    public String getPassword() {
        return password;
    }
    
    public String getToken() {
        return token;
    }
    
    public String getReauthToken() {
        return reauthToken;
    }
    
    @JsonIgnore
    public AccountId getAccountId() {
        if (email != null) {
            return AccountId.forEmail(appId, email);
        } else if (phone != null) {
            return AccountId.forPhone(appId, phone);
        } else if (externalId != null) {
            return AccountId.forExternalId(appId, externalId);
        }
        throw new IllegalArgumentException("SignIn not constructed with enough information to retrieve an account");
    }
    
    public static class Builder {
        private String username;
        private String email;
        private Phone phone;
        private String externalId;
        private String password;
        private String appId;
        private String token;
        private String reauthToken;
        
        public Builder withSignIn(SignIn signIn) {
            this.email = signIn.email;
            this.phone = signIn.phone;
            this.externalId = signIn.externalId;
            this.password = signIn.password;
            this.appId = signIn.appId;
            this.token = signIn.token;
            this.reauthToken = signIn.reauthToken;
            return this;
        }
        public Builder withUsername(String username) {
            this.username = username;    
            return this;
        }
        public Builder withEmail(String email) {
            this.email = email;
            return this;
        }
        public Builder withPhone(Phone phone) {
            this.phone = phone;
            return this;
        }
        public Builder withExternalId(String externalId) {
            this.externalId = externalId;
            return this;
        }
        public Builder withPassword(String password) {
            this.password = password;
            return this;
        }
        @JsonAlias("study")
        public Builder withAppId(String appId) {
            this.appId = appId;
            return this;
        }
        public Builder withToken(String token) {
            this.token = token;
            return this;
        }
        public Builder withReauthToken(String reauthToken) {
            this.reauthToken = reauthToken;
            return this;
        }
        public SignIn build() {
            String identifier = (username != null) ? username : email;
            return new SignIn(appId, identifier, phone, externalId, password, token, reauthToken);
        }
    }
}