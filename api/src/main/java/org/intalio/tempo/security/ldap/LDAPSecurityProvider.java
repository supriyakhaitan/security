/**
 * Copyright (C) 2003, Intalio Inc.
 *
 * The program(s) herein may be used and/or copied only with the
 * written permission of Intalio Inc. or in accordance with the terms
 * and conditions stipulated in the agreement/contract under which the
 * program(s) have been supplied.
 *
 * $Id: LDAPSecurityProvider.java,v 1.10 2004/02/21 00:10:22 boisvert Exp $
 */

package org.intalio.tempo.security.ldap;

import java.io.FileInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.intalio.tempo.security.authentication.AuthenticationException;
import org.intalio.tempo.security.authentication.provider.AuthenticationProvider;
import org.intalio.tempo.security.impl.Realms;
import org.intalio.tempo.security.provider.SecurityProvider;
import org.intalio.tempo.security.rbac.RBACException;
import org.intalio.tempo.security.rbac.provider.RBACProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.intalio.bpms.license.IntalioSigner;

/**
 * An implementation of SecurityProvider that is backed by a LDAP directory
 * 
 * @author <a href="http://www.intalio.com">&copy; Intalio Inc.</a>
 * @author Thomas Yip
 */
public class LDAPSecurityProvider implements SecurityProvider {
    
    protected static final String DEFAULT_LDAP_FACTORY = "com.sun.jndi.ldap.LdapCtxFactory";
    
    protected static final String DEFAULT_PROVIDER_URL = "ldap://localhost:389";
    
    protected static final String DEFAULT_SECURITY_PRINCIPAL = "username";
    
    protected static final String DEFAULT_SECURITY_CREDENTIALS = "password";
    
    protected static final Logger LOG = LoggerFactory.getLogger("tempo.security");
    
    private  Set<String> _workflowAdminUsers;
    private  Set<String> _workflowAdminRoles;

	private String                      _name = "LDAP";

    private String                      _default;
    
    private Hashtable<String,String>    _env;
    
    private Map<String,String>          _realms;
    
    private Map<String,LDAPRBACProvider> _rbacs;
    
    private Map<String,LDAPAuthenticationProvider> _auths;

    /**
     * Constructor
     */
    public LDAPSecurityProvider() {
        super();
        _realms = new HashMap<String,String>();
        _rbacs  = new HashMap<String,LDAPRBACProvider>();
        _auths  = new HashMap<String,LDAPAuthenticationProvider>(); 
    }

    public void setPropertiesFile( String filename ) throws Exception {
    	if(filename.startsWith("$")){
    		String[] fileStrings = filename.split("/");
    		String configDir = System.getProperty("org.intalio.tempo.configDirectory");
    		filename = configDir + "/" + fileStrings[fileStrings.length-1];
    	}
        Properties props = new Properties();
        props.load( new FileInputStream( filename ) );
        initialize( props );
    }
    

    public void setWorkflowAdminUsers(Set<String> _workflowAdminUsers) {
        this._workflowAdminUsers = _workflowAdminUsers;
    }
    
    public void setWorkflowAdminRoles(Set<String> _workflowAdminRoles) {
        this._workflowAdminRoles = _workflowAdminRoles;
    }

    
    // Spring init method
    @SuppressWarnings("unchecked")
    public void initialize(Object config)
    throws AuthenticationException, RBACException {
        
        if ( config instanceof Map ) {
            // take conf object as the InitialContext's environment
            _env = new Hashtable<String,String>((Map)config);
        } else {
            _env = new Hashtable<String,String>();
        }
        
        // ensure each necessary property are set, or default is used
        if (!_env.containsKey(Context.INITIAL_CONTEXT_FACTORY)) {
            _env.put(Context.INITIAL_CONTEXT_FACTORY, DEFAULT_LDAP_FACTORY);
            LOG.info("\""+Context.INITIAL_CONTEXT_FACTORY+"\" is not configured, default \""+DEFAULT_LDAP_FACTORY+"\" is used!");
        }
        if (!_env.containsKey(Context.PROVIDER_URL)) {
            _env.put(Context.PROVIDER_URL, DEFAULT_PROVIDER_URL);
            LOG.info("\""+Context.PROVIDER_URL+"\" is not configured, default \""+DEFAULT_PROVIDER_URL+"\" is used!");
        }
        if (!_env.containsKey(Context.SECURITY_PRINCIPAL)) {
            _env.put(Context.SECURITY_PRINCIPAL, DEFAULT_SECURITY_PRINCIPAL);
            LOG.info("\""+Context.SECURITY_PRINCIPAL+"\" is not configured, default \""+DEFAULT_SECURITY_PRINCIPAL+"\" is used!");
        }
        if (!_env.containsKey(Context.SECURITY_CREDENTIALS)) {
            _env.put(Context.SECURITY_CREDENTIALS, DEFAULT_SECURITY_CREDENTIALS);
            LOG.info("\""+Context.SECURITY_CREDENTIALS+"\" is not configured, default \""+DEFAULT_SECURITY_CREDENTIALS+"\" is used!");
        }              
        
        DirContext root = null;
        try {
            root = getRootContext();

            if (!IntalioSigner.hasOption("enterprise")) {
                detectServerType(root);
            }
        } catch ( NamingException ne ) {
            throw new AuthenticationException("Error creating initial context!",ne);
        } finally {
            close(root);
        }

        initRealms(_env);
    }
    
    private void detectServerType(DirContext context) {
        try {
            Attributes attrs = context.getAttributes("", new String[] {
                    "vendorVersion", "vendorName", "highestCommittedUSN",
                    "rootDomainNamingContext", "structuralObjectClass",
                    "forestFunctionality", "isGlobalCatalogReady" });
            String vendorName = getStringAttrValue(attrs, "vendorName");
            if (null != vendorName) {
                vendorName = vendorName.toLowerCase();
                if (vendorName.contains("ibm")) {
                    String msg = "Intalio|bpms license present in server does not support Microsoft Active Directory"
                            + ". Server will shutdown now. Please contact support@intalio.com "
                            + "for assistance, or sign up for Enterprise Edition trial "
                            + "(http://www.intalio.com/try-intaliobpms-enterprise-edition-free-for-45-days/).";
                    LOG.error(msg);
                    System.exit(0);
                }

                if (vendorName.contains("novell")) {
                    String msg = "Intalio|bpms license present in server does not support Microsoft Active Directory"
                            + ". Server will shutdown now. Please contact support@intalio.com "
                            + "for assistance, or sign up for Enterprise Edition trial "
                            + "(http://www.intalio.com/try-intaliobpms-enterprise-edition-free-for-45-days/).";
                    LOG.error(msg);
                    System.exit(0);
                }

                if (vendorName.contains("unboundid")) {
                }
            }

            String vendorVersion = getStringAttrValue(attrs, "vendorVersion");
            if (vendorVersion != null) {
                vendorVersion = vendorVersion.toLowerCase();
                if (vendorVersion.contains("opends")) {
                }
                if (vendorVersion.contains("opendj")) {
                }
                if (vendorVersion.contains("sun")
                        && vendorVersion.contains("directory")) {
                }
            } else {
                String hUSN = getStringAttrValue(attrs, "highestCommittedUSN");
                String sOC = getStringAttrValue(attrs, "structuralObjectClass");

                if (hUSN != null) {
                    // Windows Active Directory

                    String msg = "Intalio|bpms license present in server does not support Microsoft Active Directory"
                            + ". Server will shutdown now. Please contact support@intalio.com "
                            + "for assistance, or sign up for Enterprise Edition trial "
                            + "(http://www.intalio.com/try-intaliobpms-enterprise-edition-free-for-45-days/).";
                    LOG.error(msg);
                    System.exit(0);
                } else if (sOC != null
                        && sOC.equalsIgnoreCase("OpenLDAProotDSE")) {
                }
            }
        } catch (NamingException e) {
            // No need to handle this exception
        }
    }

    public static String getStringAttrValue(Attributes ldapAttrs,
            String ldapAttrName) {
        Attribute attr = ldapAttrs.get(ldapAttrName);
        if (attr != null) {
            try {
                return (String) attr.get();
            } catch (NamingException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    public enum ServerType {
        SUN_DSEE, OPENDS, OPENDJ, IBM, MSAD, MSAD_LDS, MSAD_GC, NOVELL, UNBOUNDID, OPENLDAP, UNKNOWN
    }

    /**
     * @see org.intalio.tempo.security.provider.SecurityProvider#getName()
     */
    public String getName() {
        return _name;
    }
    
    /**
     * @see org.intalio.tempo.security.provider.SecurityProvider#setName(java.lang.String)
     */
	public void setName( String name ) {
		_name = name;
	}

    /**
     * Obtain the root _context of this _context
     *  
     * @throws NamingException
     */
    synchronized DirContext getRootContext() throws NamingException {
        return new InitialDirContext(_env);
    }

    /**
     * 
     * @param branch
     * @throws NamingException
     */
    public synchronized DirContext getContext(String branch) throws NamingException {
        DirContext root = null;
        try {
            root = getRootContext();
            return (DirContext)root.lookup(branch);
        } catch (NamingException ne) {
            throw ne;
        } finally {
            close(root);
        }
    }
    
    /**
     * 
     * @param env
     * @throws IllegalArgumentException
     */
    private void initRealms(Map<String,String> env) throws IllegalArgumentException {
        Map<String,String> realms = new HashMap<String,String>();
        _default = readProperties(LDAPProperties.SECURITY_LDAP_REALM, env, realms);

        if (realms.size()==0||_default==null)
            throw new IllegalArgumentException("Realm is not defined!");
        _realms = realms;       
    }
    
    private static String readProperties(String keyRoot, Map<String,String> env, Map<String,String> result) {
        String def = null;
        for (int i = 0; true; i++) {
            String key   = keyRoot+'.'+i;
            String value = (String)env.get(key);
            if (value==null)
                break;
            int colon = value.indexOf(':');
            if (colon==-1 || colon==0 || colon==value.length()-1)
                throw new IllegalArgumentException("Format is not reconized! key: "+key+" value: "+value);
            String realm = value.substring(0, colon).trim();
            String dn    = value.substring(colon+1).trim();
            if (realm.length()==0 || dn.length()==0)
                throw new IllegalArgumentException("Format is not reconized! key: "+key+" value: "+value);            
            if (i==0)
                def=realm;
            result.put(realm, dn);
        }
        return def;
    }

    /**
     * @see org.intalio.tempo.security.provider.SecurityProvider#getRealms()
     */
    public String[] getRealms() throws AuthenticationException, RBACException {
        Set<String> set = _realms.keySet();
        String[] realms = new String[set.size()];
        set.toArray(realms);
        return realms;
    }

    /**
     * method to get realm for input dn.
     * @param dn String
     * @return realm String
     */
    public final String getRealm(final String dn) {
        Set<String> realms = _realms.keySet();
        for (String realm : realms) {
            if (dn.equals(_realms.get(realm))) {
                return realm;
            }
        }
        return _default;
    }

    /**
     * @see org.intalio.tempo.security.provider.SecurityProvider#getRBACProvider(java.lang.String)
     */
    public synchronized RBACProvider getRBACProvider(String realm)
            throws RBACException {

        if (realm == null || "".equals(realm))
            realm = _default;
        if (Realms.isCaseSensitive()) {
            if (!_realms.containsKey(realm))
                throw new RBACException("Realm, " + realm
                        + ", is not supported by this Security Provider!");
        } else {
            if (!_realms.containsKey(realm)) {
                realm = fetchRealmKeyIgnoreCase(realm);
                if (realm == null)
                    throw new RBACException("Realm, " + realm
                            + ", is not supported by this Security Provider!");
            }
        }
        if (_rbacs.containsKey(realm))
            return (RBACProvider) _rbacs.get(realm);

        Context context = null;
        try {
            String dn = _realms.get(realm);

            // make sure the sub _context exist
            context = getContext(dn);

            LDAPQueryEngine engine = new LDAPQueryEngine(this, dn);
            LDAPRBACProvider rbac = new LDAPRBACProvider(realm, engine, dn);
            rbac.initialize(_env);
            _rbacs.put(realm, rbac);

            return rbac;
        } catch (NamingException ne) {
            throw new RBACException(ne);
        } finally {
            close(context);
        }
    }
    
    /**
     * @see org.intalio.tempo.security.provider.SecurityProvider#getAuthenticationProvider(java.lang.String)
     */
    public synchronized AuthenticationProvider getAuthenticationProvider(String realm)
 throws AuthenticationException {

        if (realm == null || "".equals(realm))
            realm = _default;
        if (Realms.isCaseSensitive()) {
            if (!_realms.containsKey(realm))
                throw new AuthenticationException("Realm, " + realm
                        + ", is not supported by this Security Provider!");
        } else {
            if (!_realms.containsKey(realm)) {
                realm = fetchRealmKeyIgnoreCase(realm);
                if (realm == null)
                    throw new AuthenticationException("Realm, " + realm
                            + ", is not supported by this Security Provider!");
            }
        }

        if (_auths.containsKey(realm))
            return (LDAPAuthenticationProvider) _auths.get(realm);

        Context context = null;
        try {
            String dn = _realms.get(realm);

            // make sure the sub _context exist
            context = getContext(dn);

            LDAPQueryEngine engine = new LDAPQueryEngine(this, dn);
            LDAPAuthenticationProvider auth = new LDAPAuthenticationProvider(
                    realm, engine, dn, _env);
            auth.setWorkflowAdminRoles(_workflowAdminRoles);
            auth.setWorkflowAdminUsers(_workflowAdminUsers);
            auth.initialize(_env);
            _auths.put(realm, auth);

            return auth;
        } catch (NamingException ne) {
            throw new AuthenticationException(ne);
        } finally {
            close(context);
        }
    }

    /**
     * @see org.intalio.tempo.security.provider.SecurityProvider#dispose()
     */
    public void dispose() throws RBACException {
        RBACException except = null;
        for (Iterator itor=_rbacs.values().iterator(); itor.hasNext(); ) {
            LDAPRBACProvider rbac = (LDAPRBACProvider)itor.next();
            try {
                rbac.dispose();
            } catch (RBACException re) {
                LOG.warn(re.getMessage(),re);
                except = re;
            }
        }
        _rbacs = null;
        for (Iterator itor=_auths.values().iterator(); itor.hasNext(); ) {
            LDAPAuthenticationProvider auth = (LDAPAuthenticationProvider)itor.next();
            try {
                auth.dispose();
            } catch (AuthenticationException ae) {
                if ( except==null ) {
                    except = new RBACException(ae);
                } else {
                    LOG.warn(ae.getMessage(),ae);
                }
            }
            _auths = null;
        }
    }
    
    static final void close(Context context) {
        if (context != null) { 
            try {
                context.close();
            } catch (Exception except) {
                // ignore
            }
        }
    }

    @Override
    public Set<String> getAttributes(String forObject) throws RBACException {
        Set<String> properties = new HashSet<String>();
        String propertyName = "";
        String id = "";
        if (forObject.equals("user")) {
            propertyName = LDAPProperties.SECURITY_LDAP_USER_PROP;
            id = LDAPProperties.SECURITY_LDAP_USER_ID;
            if (_env.containsKey(LDAPProperties.SECURITY_LDAP_USER_CREDENTIAL+".0")) {
                properties.add( _env.get(LDAPProperties.SECURITY_LDAP_USER_CREDENTIAL+".0").split(":")[0]);
            } else if (_env.containsKey(LDAPProperties.SECURITY_LDAP_USER_CREDENTIAL+".1")) {
                properties.add( _env.get(LDAPProperties.SECURITY_LDAP_USER_CREDENTIAL+".1").split(":")[0]);
            }
        } else if (forObject.equals("role")) {
            propertyName = LDAPProperties.SECURITY_LDAP_ROLE_PROP;
            id = LDAPProperties.SECURITY_LDAP_ROLE_ID;
        }
        for (int i = 0; true; i++) {
            String key = propertyName + '.' + i;
            if (_env.containsKey(key)) {
                String value = (String) _env.get(key);
                String[] temp = value.split(":");
                if(temp != null && temp.length > 0) {
                    if (temp.length == 1) {
                        properties.add(value.split(":")[0]);
                    } else {
                        properties.add(value.split(":")[1]);
                    }
                }
            } else {
                break;
            }
        }
        properties.remove(id);
        return properties;
    }

    private String fetchRealmKeyIgnoreCase(String realm) {
        String realmKey = null;
        Set<String> keys = _realms.keySet();
        for (String key : keys) {
            if (key.equalsIgnoreCase(realm)) {
                realmKey = key;
                break;
            }
        }
        return realmKey;
    }
}
