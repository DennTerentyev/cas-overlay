/*
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.adaptors.jdbc;

import java.security.GeneralSecurityException;

import org.jasig.cas.authentication.HandlerResult;
import org.jasig.cas.authentication.PreventedException;
import org.jasig.cas.authentication.Credential;
import org.jasig.cas.authentication.UsernamePasswordCredential;
import org.jasig.cas.authentication.OneTimePasswordCredential;
import org.jasig.cas.authentication.OpenScienceFrameworkCredential;
import org.jasig.cas.authentication.BasicCredentialMetaData;
import org.jasig.cas.authentication.principal.SimplePrincipal;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.jasig.cas.authentication.handler.NoOpPrincipalNameTransformer;
import org.jasig.cas.authentication.handler.PrincipalNameTransformer;


// import org.jasig.cas.adaptors.jdbc.AbstractJdbcUsernamePasswordAuthenticationHandler;

import javax.security.auth.login.AccountNotFoundException;
import javax.security.auth.login.FailedLoginException;
import javax.validation.constraints.NotNull;

import java.util.Map;
import java.util.List;
import org.jasig.cas.Message;
import org.jasig.cas.authentication.principal.Principal;

import org.jasig.cas.authentication.oath.TotpUtils;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public class OSFDatabaseAuthenticationHandler extends AbstractJdbcAuthenticationHandler
        implements InitializingBean {

    @NotNull
    private PrincipalNameTransformer principalNameTransformer = new NoOpPrincipalNameTransformer();

    @NotNull
    private String fieldUser;

    @NotNull
    private String fieldPassword;

    @NotNull
    private String fieldTotpSecretKey;

    // @NotNull
    private String fieldTotpInterval;

    // @NotNull
    private String fieldTotpIntervalWindow;

    @NotNull
    private String tableUsers;

    private String sql;

    /**
     * {@inheritDoc}
     **/
    @Override
    protected final HandlerResult doAuthentication(final Credential credential)
            throws GeneralSecurityException, PreventedException {
        final OpenScienceFrameworkCredential osfCredential = (OpenScienceFrameworkCredential)credential;
        if (osfCredential.getUsername() == null) {
            throw new AccountNotFoundException("Username is null.");
        }
        final String transformedUsername = this.principalNameTransformer.transform(osfCredential.getUsername());
        if (transformedUsername == null) {
            throw new AccountNotFoundException("Transformed username is null.");
        }
        osfCredential.setUsername(transformedUsername);

        return authenticateInternal(osfCredential);
    }

    protected final HandlerResult authenticateInternal(final OpenScienceFrameworkCredential credential)
            throws GeneralSecurityException, PreventedException {
        final String username = credential.getUsername();
        final String plainTextPassword = credential.getPassword();
        final String oneTimePassword = credential.getOneTimePassword();
        final String encryptedPassword;
        final String totpSecretKey;
        try {
            final Map<String, String> result = getJdbcTemplate().queryForObject(this.sql, Map.class, username);
            encryptedPassword = result.get(this.fieldPassword);
            totpSecretKey = result.get(this.fieldTotpSecretKey);
        } catch (final IncorrectResultSizeDataAccessException e) {
            if (e.getActualSize() == 0) {
                throw new AccountNotFoundException(username + " not found with SQL query");
            } else {
                throw new FailedLoginException("Multiple records found for " + username);
            }
        } catch (final DataAccessException e) {
            throw new PreventedException("SQL exception while executing query for " + username, e);
        }
        if (!BCrypt.checkpw(plainTextPassword, encryptedPassword)) {
            throw new FailedLoginException(username + " invalid password.");
        }
        if (totpSecretKey != null) {
            Integer totpInterval = 0;
            Integer totpIntervalWindow = 300;

            try {
                if (!TotpUtils.checkCode(totpSecretKey, Long.valueOf(oneTimePassword), totpInterval, totpIntervalWindow)) {
                    throw new FailedLoginException(username + " invalid time-based one time password.");
                }
            } catch (NoSuchAlgorithmException ex) {
                throw new FailedLoginException(username + " invalid time-based one time password.");
                // logger.error(ex.getMessage());
                // return false;
            } catch (InvalidKeyException ex) {
                throw new FailedLoginException(username + " invalid time-based one time password.");
                // logger.error(ex.getMessage());
                // return false;
            }
        }

        // if (totpSecret != null && Totp.checkpw(oneTimePassword, totpSecretKey, totpInterval, totpInervalWindow)) {
        //     throw new FailedLoginException(username + " invalid time-based one time password.");
        // }
        return createHandlerResult(credential, this.principalFactory.createPrincipal(username), null);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.sql = "SELECT " + this.fieldPassword + ", " + this.fieldTotpSecretKey +
            " FROM " + this.tableUsers +
            " WHERE LOWER(" + this.fieldUser + ") = LOWER(?) AND active = TRUE";
    }


    /**
     * Helper method to construct a handler result
     * on successful authentication events.
     *
     * @param credential the credential on which the authentication was successfully performed.
     * Note that this credential instance may be different from what was originally provided
     * as transformation of the username may have occurred, if one is in fact defined.
     * @param principal the resolved principal
     * @param warnings the warnings
     * @return the constructed handler result
     */
    protected final HandlerResult createHandlerResult(final Credential credential, final Principal principal,
            final List<Message> warnings) {
        return new HandlerResult(this, new BasicCredentialMetaData(credential), principal, warnings);
    }

    protected final PrincipalNameTransformer getPrincipalNameTransformer() {
        return this.principalNameTransformer;
    }

    public final void setPrincipalNameTransformer(final PrincipalNameTransformer principalNameTransformer) {
        this.principalNameTransformer = principalNameTransformer;
    }

    /**
     * @param fieldPassword The fieldPassword to set.
     */
    public final void setFieldPassword(final String fieldPassword) {
        this.fieldPassword = fieldPassword;
    }

    /**
     * @param fieldUser The fieldUser to set.
     */
    public final void setFieldUser(final String fieldUser) {
        this.fieldUser = fieldUser;
    }

    /**
     * @param fieldTotp The fieldTotpSecretKey to set.
     */
    public final void setFieldTotpSecretKey(final String fieldTotpSecretKey) {
        this.fieldTotpSecretKey = fieldTotpSecretKey;
    }

    /**
     * @param tableUsers The tableUsers to set.
     */
    public final void setTableUsers(final String tableUsers) {
        this.tableUsers = tableUsers;
    }

    /**
     * {@inheritDoc}
     * @return True if credential is a {@link UsernamePasswordCredential}, false otherwise.
     */
    @Override
    public boolean supports(final Credential credential) {
        return credential instanceof OpenScienceFrameworkCredential;
    }
}
