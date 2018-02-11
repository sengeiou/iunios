/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.mail.store;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;

import com.android.email.LegacyConversions;
import com.android.email.Preferences;
import com.android.email.mail.Store;
import com.android.email.mail.store.imap.ImapConstants;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapString;
import com.android.email.mail.transport.MailTransport;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;
import com.beetstra.jutf7.CharsetProvider;
import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;


/**
 * <pre>
 * TODO Need to start keeping track of UIDVALIDITY
 * TODO Need a default response handler for things like folder updates
 * TODO In fetch(), if we need a ImapMessage and were given
 *      something else we can try to do a pre-fetch first.
 * TODO Collect ALERT messages and show them to users.
 *
 * ftp://ftp.isi.edu/in-notes/rfc2683.txt When a client asks for
 * certain information in a FETCH command, the server may return the requested
 * information in any order, not necessarily in the order that it was requested.
 * Further, the server may return the information in separate FETCH responses
 * and may also return information that was not explicitly requested (to reflect
 * to the client changes in the state of the subject message).
 * </pre>
 */
public class ImapStore extends Store {
    /** Charset used for converting folder names to and from UTF-7 as defined by RFC 3501. */
    private static final Charset MODIFIED_UTF_7_CHARSET =
            new CharsetProvider().charsetForName("X-RFC-3501");

    @VisibleForTesting static String sImapId = null;
    @VisibleForTesting String mPathPrefix;
    @VisibleForTesting String mPathSeparator;

    //Aurora <shihao> <2014-11-14> for imap folder Merge local_folder begin
    static final HashMap<Integer,String> imapFolderNameMap = new HashMap<Integer, String>();
    //Aurora <shihao> <2014-11-14> for imap folder Merge local_folder end
    
    private final ConcurrentLinkedQueue<ImapConnection> mConnectionPool =
            new ConcurrentLinkedQueue<ImapConnection>();

    /**
     * Static named constructor.
     */
    public static Store newInstance(Account account, Context context) throws MessagingException {
        return new ImapStore(context, account);
    }

    /**
     * Creates a new store for the given account. Always use
     * {@link #newInstance(Account, Context)} to create an IMAP store.
     */
    private ImapStore(Context context, Account account) throws MessagingException {
        mContext = context;
        mAccount = account;

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(context);
        if (recvAuth == null) {
            throw new MessagingException("No HostAuth in ImapStore?");
        }
        mTransport = new MailTransport(context, "IMAP", recvAuth);

        String[] userInfo = recvAuth.getLogin();
        if (userInfo != null) {
            mUsername = userInfo[0];
            mPassword = userInfo[1];
        } else {
            mUsername = null;
            mPassword = null;
        }
        mPathPrefix = recvAuth.mDomain;
    }

    @VisibleForTesting
    Collection<ImapConnection> getConnectionPoolForTest() {
        return mConnectionPool;
    }

    /**
     * For testing only.  Injects a different root transport (it will be copied using
     * newInstanceWithConfiguration() each time IMAP sets up a new channel).  The transport
     * should already be set up and ready to use.  Do not use for real code.
     * @param testTransport The Transport to inject and use for all future communication.
     */
    @VisibleForTesting
    void setTransportForTest(MailTransport testTransport) {
        mTransport = testTransport;
    }

    /**
     * Return, or create and return, an string suitable for use in an IMAP ID message.
     * This is constructed similarly to the way the browser sets up its user-agent strings.
     * See RFC 2971 for more details.  The output of this command will be a series of key-value
     * pairs delimited by spaces (there is no point in returning a structured result because
     * this will be sent as-is to the IMAP server).  No tokens, parenthesis or "ID" are included,
     * because some connections may append additional values.
     *
     * The following IMAP ID keys may be included:
     *   name                   Android package name of the program
     *   os                     "android"
     *   os-version             "version; model; build-id"
     *   vendor                 Vendor of the client/server
     *   x-android-device-model Model (only revealed if release build)
     *   x-android-net-operator Mobile network operator (if known)
     *   AGUID                  A device+account UID
     *
     * In addition, a vendor policy .apk can append key/value pairs.
     *
     * @param userName the username of the account
     * @param host the host (server) of the account
     * @param capabilities a list of the capabilities from the server
     * @return a String for use in an IMAP ID message.
     */
    public static String getImapId(Context context, String userName, String host,
            String capabilities) {
        // The first section is global to all IMAP connections, and generates the fixed
        // values in any IMAP ID message
        synchronized (ImapStore.class) {
            if (sImapId == null) {
                TelephonyManager tm =
                        (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                String networkOperator = tm.getNetworkOperatorName();
                if (networkOperator == null) networkOperator = "";

                sImapId = makeCommonImapId(context.getPackageName(), Build.VERSION.RELEASE,
                        Build.VERSION.CODENAME, Build.MODEL, Build.ID, Build.MANUFACTURER,
                        networkOperator);
            }
        }

        // This section is per Store, and adds in a dynamic elements like UID's.
        // We don't cache the result of this work, because the caller does anyway.
        StringBuilder id = new StringBuilder(sImapId);

        // Optionally add any vendor-supplied id keys
        String vendorId = VendorPolicyLoader.getInstance(context).getImapIdValues(userName, host,
                capabilities);
        if (vendorId != null) {
            id.append(' ');
            id.append(vendorId);
        }

        // Generate a UID that mixes a "stable" device UID with the email address
        try {
            String devUID = Preferences.getPreferences(context).getDeviceUID();
            MessageDigest messageDigest;
            messageDigest = MessageDigest.getInstance("SHA-1");
            messageDigest.update(userName.getBytes());
            messageDigest.update(devUID.getBytes());
            byte[] uid = messageDigest.digest();
            String hexUid = Base64.encodeToString(uid, Base64.NO_WRAP);
            id.append(" \"AGUID\" \"");
            id.append(hexUid);
            id.append('\"');
        } catch (NoSuchAlgorithmException e) {
            LogUtils.d(Logging.LOG_TAG, "couldn't obtain SHA-1 hash for device UID");
        }
        return id.toString();
    }

    /**
     * Helper function that actually builds the static part of the IMAP ID string.  This is
     * separated from getImapId for testability.  There is no escaping or encoding in IMAP ID so
     * any rogue chars must be filtered here.
     *
     * @param packageName context.getPackageName()
     * @param version Build.VERSION.RELEASE
     * @param codeName Build.VERSION.CODENAME
     * @param model Build.MODEL
     * @param id Build.ID
     * @param vendor Build.MANUFACTURER
     * @param networkOperator TelephonyManager.getNetworkOperatorName()
     * @return the static (never changes) portion of the IMAP ID
     */
    @VisibleForTesting
    static String makeCommonImapId(String packageName, String version,
            String codeName, String model, String id, String vendor, String networkOperator) {

        // Before building up IMAP ID string, pre-filter the input strings for "legal" chars
        // This is using a fairly arbitrary char set intended to pass through most reasonable
        // version, model, and vendor strings: a-z A-Z 0-9 - _ + = ; : . , / <space>
        // The most important thing is *not* to pass parens, quotes, or CRLF, which would break
        // the format of the IMAP ID list.
        Pattern p = Pattern.compile("[^a-zA-Z0-9-_\\+=;:\\.,/ ]");
        packageName = p.matcher(packageName).replaceAll("");
        version = p.matcher(version).replaceAll("");
        codeName = p.matcher(codeName).replaceAll("");
        model = p.matcher(model).replaceAll("");
        id = p.matcher(id).replaceAll("");
        vendor = p.matcher(vendor).replaceAll("");
        networkOperator = p.matcher(networkOperator).replaceAll("");

        // "name" "com.android.email"
        StringBuffer sb = new StringBuffer("\"name\" \"");
        sb.append(packageName);
        sb.append("\"");

        // "os" "android"
        sb.append(" \"os\" \"android\"");

        // "os-version" "version; build-id"
        sb.append(" \"os-version\" \"");
        if (version.length() > 0) {
            sb.append(version);
        } else {
            // default to "1.0"
            sb.append("1.0");
        }
        // add the build ID or build #
        if (id.length() > 0) {
            sb.append("; ");
            sb.append(id);
        }
        sb.append("\"");

        // "vendor" "the vendor"
        if (vendor.length() > 0) {
            sb.append(" \"vendor\" \"");
            sb.append(vendor);
            sb.append("\"");
        }

        // "x-android-device-model" the device model (on release builds only)
        if ("REL".equals(codeName)) {
            if (model.length() > 0) {
                sb.append(" \"x-android-device-model\" \"");
                sb.append(model);
                sb.append("\"");
            }
        }

        // "x-android-mobile-net-operator" "name of network operator"
        if (networkOperator.length() > 0) {
            sb.append(" \"x-android-mobile-net-operator\" \"");
            sb.append(networkOperator);
            sb.append("\"");
        }

        return sb.toString();
    }


    @Override
    public Folder getFolder(String name) {
        return new ImapFolder(this, name);
    }

    /**
     * Creates a mailbox hierarchy out of the flat data provided by the server.
     */
    @VisibleForTesting
    static void createHierarchy(HashMap<String, ImapFolder> mailboxes) {
        Set<String> pathnames = mailboxes.keySet();
        for (String path : pathnames) {
            final ImapFolder folder = mailboxes.get(path);
            final Mailbox mailbox = folder.mMailbox;
            int delimiterIdx = mailbox.mServerId.lastIndexOf(mailbox.mDelimiter);
            long parentKey = Mailbox.NO_MAILBOX;
            String parentPath = null;
            if (delimiterIdx != -1) {
                parentPath = path.substring(0, delimiterIdx);
                final ImapFolder parentFolder = mailboxes.get(parentPath);
                final Mailbox parentMailbox = (parentFolder == null) ? null : parentFolder.mMailbox;
                if (parentMailbox != null) {
                    parentKey = parentMailbox.mId;
                    parentMailbox.mFlags
                            |= (Mailbox.FLAG_HAS_CHILDREN | Mailbox.FLAG_CHILDREN_VISIBLE);
                }
            }
            mailbox.mParentKey = parentKey;
            mailbox.mParentServerId = parentPath;
        }
    }

    /**
     * Creates a {@link Folder} and associated {@link Mailbox}. If the folder does not already
     * exist in the local database, a new row will immediately be created in the mailbox table.
     * Otherwise, the existing row will be used. Any changes to existing rows, will not be stored
     * to the database immediately.
     * @param accountId The ID of the account the mailbox is to be associated with
     * @param mailboxPath The path of the mailbox to add
     * @param delimiter A path delimiter. May be {@code null} if there is no delimiter.
     * @param selectable If {@code true}, the mailbox can be selected and used to store messages.
     * @param mailbox If not null, mailbox is used instead of querying for the Mailbox.
     */
    private ImapFolder addMailbox(Context context, long accountId, String mailboxPath,
            char delimiter, boolean selectable, Mailbox mailbox) {
        ImapFolder folder = (ImapFolder) getFolder(mailboxPath);
        
        //Aurora <shihao> <2014-11-26> for Update Mailbox Table from imap_server begin
        int maiboxType = -1;
        if(mailbox == null){
            maiboxType = LegacyConversions.getImapFolderType(mContext, mailboxPath);
            switch (maiboxType) {
    			case Mailbox.TYPE_SENT:
    			case Mailbox.TYPE_TRASH:
    			case Mailbox.TYPE_DRAFTS:
    				if(mailbox == null)
    					mailbox = Mailbox.restoreMailboxOfType(mContext, accountId, maiboxType);
    				
    				/**
    				 * 1.if the Mailbox mServerId is "已存在
    				 * 		a.if mailboxPath == 已存在 update mailbox
    				 * 		b.if mailboxPath != 已存在 return null
    				 * 2.if the Mailbox mServerId is not ”已存在" , go to update mailbox directly
    				 */
    				if(maiboxType == Mailbox.TYPE_SENT && mailbox != null){
    					String newSentMailboxName = LegacyConversions.getNewSentmailboxName(context);
    					if(mailbox.mServerId.equalsIgnoreCase(newSentMailboxName)
    							&& !mailboxPath.equalsIgnoreCase(newSentMailboxName)){
    						return null;
    					}
    				}
    				
    				break;
    			case Mailbox.TYPE_STARRED:
    				return null;
    			default:
    				maiboxType = -1;
    				break;
    		}
        }
        //Aurora <shihao> <2014-11-26> for Update Mailbox Table from imap_server end
      
        if (mailbox == null) {
            mailbox = Mailbox.getMailboxForPath(context, accountId, mailboxPath);
        }
        if (mailbox.isSaved()) {
            // existing mailbox
            // mailbox retrieved from database; save hash _before_ updating fields
            folder.mHash = mailbox.getHashes();
        }
        //Aurora <shihao> <2014-11-26> for Update Mailbox Table from imap_server begin
        if(maiboxType == -1)
        	maiboxType = LegacyConversions.inferMailboxTypeFromName(context, mailboxPath);
        updateMailbox(mailbox, accountId, mailboxPath, delimiter, selectable,
                maiboxType);
        //Aurora <shihao> <2014-11-26> for Update Mailbox Table from imap_server end
        
        if (folder.mHash == null) {
            // new mailbox
            // save hash after updating. allows tracking changes if the mailbox is saved
            // outside of #saveMailboxList()
            folder.mHash = mailbox.getHashes();
            // We must save this here to make sure we have a valid ID for later
            mailbox.save(mContext);
        }
        folder.mMailbox = mailbox;
        return folder;
    }

    /**
     * Persists the folders in the given list.
     */
    private static void saveMailboxList(Context context, HashMap<String, ImapFolder> folderMap) {
        for (ImapFolder imapFolder : folderMap.values()) {
            imapFolder.save(context);
        }
    }

    @Override
    public Folder[] updateFolders() throws MessagingException {
        // TODO: There is nothing that ever closes this connection. Trouble is, it's not exactly
        // clear when we should close it, we'd like to keep it open until we're really done
        // using it.
        ImapConnection connection = getConnection();
        try {
            HashMap<String, ImapFolder> mailboxes = new HashMap<String, ImapFolder>();
            // Establish a connection to the IMAP server; if necessary
            // This ensures a valid prefix if the prefix is automatically set by the server
            connection.executeSimpleCommand(ImapConstants.NOOP);
            String imapCommand = ImapConstants.LIST + " \"\" \"*\"";
            if (mPathPrefix != null) {
                imapCommand = ImapConstants.LIST + " \"\" \"" + mPathPrefix + "*\"";
            }
            //Aurora <shihao> <2014-11-27> for Change Mailbox.TYPE_SENT mServerId to "已发送" begin
            final Mailbox sent = Mailbox.restoreMailboxOfType(mContext, mAccount.mId, Mailbox.TYPE_SENT);
            if(sent != null){
            	String newSentMailboxName = LegacyConversions.getNewSentmailboxName(mContext);
            	if(!sent.mServerId.equalsIgnoreCase(newSentMailboxName)){
            		Folder remoteFolder = getFolder(newSentMailboxName);
            		if(remoteFolder.exists()){
            			sent.mServerId = newSentMailboxName;
            			sent.mDisplayName = newSentMailboxName;
                        sent.update(mContext, sent.toContentValues());
            		}
            	}
            }
            //Aurora <shihao> <2014-11-27> for Change Mailbox.TYPE_SENT mServerId to "已发送" end
            
            List<ImapResponse> responses = connection.executeSimpleCommand(imapCommand);
            for (ImapResponse response : responses) {
                // S: * LIST (\Noselect) "/" ~/Mail/foo
                if (response.isDataResponse(0, ImapConstants.LIST)) {
                    // Get folder name.
                    ImapString encodedFolder = response.getStringOrEmpty(3);
                    if (encodedFolder.isEmpty()) continue;

                    String folderName = decodeFolderName(encodedFolder.getString(), mPathPrefix);

                    if (ImapConstants.INBOX.equalsIgnoreCase(folderName)) continue;

                    // Parse attributes.
                    boolean selectable =
                        !response.getListOrEmpty(1).contains(ImapConstants.FLAG_NO_SELECT);
                    String delimiter = response.getStringOrEmpty(2).getString();
                    char delimiterChar = '\0';
                    if (!TextUtils.isEmpty(delimiter)) {
                        delimiterChar = delimiter.charAt(0);
                    }
                    
                    ImapFolder folder = addMailbox(
                            mContext, mAccount.mId, folderName, delimiterChar, selectable, null);
                    if(folder != null)
                    	mailboxes.put(folderName, folder);
                    else
                    	continue;
                }
            }

            // In order to properly map INBOX -> Inbox, handle it as a special case.
            final Mailbox inbox =
                    Mailbox.restoreMailboxOfType(mContext, mAccount.mId, Mailbox.TYPE_INBOX);
            if(inbox != null){ 
		        final ImapFolder newFolder = addMailbox(
		                mContext, mAccount.mId, inbox.mServerId, '\0', true /*selectable*/, inbox);
		        mailboxes.put(ImapConstants.INBOX, newFolder);
            }

            createHierarchy(mailboxes);
            saveMailboxList(mContext, mailboxes);
            return mailboxes.values().toArray(new Folder[] {});
        } catch (IOException ioe) {
            connection.close();
            throw new MessagingException("Unable to get folder list.", ioe);
        } catch (AuthenticationFailedException afe) {
            // We do NOT want this connection pooled, or we will continue to send NOOP and SELECT
            // commands to the server
            connection.destroyResponses();
            connection = null;
            throw afe;
        } finally {
            if (connection != null) {
                poolConnection(connection);
            }
        }
    }

    @Override
    public Bundle checkSettings() throws MessagingException {
        int result = MessagingException.NO_ERROR;
        Bundle bundle = new Bundle();
        ImapConnection connection = new ImapConnection(this, mUsername, mPassword);
        try {
            connection.open();
            connection.close();
        } catch (IOException ioe) {
            bundle.putString(EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE, ioe.getMessage());
            result = MessagingException.IOERROR;
        } finally {
            connection.destroyResponses();
        }
        bundle.putInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE, result);
        return bundle;
    }

    /**
     * Returns whether or not the prefix has been set by the user. This can be determined by
     * the fact that the prefix is set, but, the path separator is not set.
     */
    boolean isUserPrefixSet() {
        return TextUtils.isEmpty(mPathSeparator) && !TextUtils.isEmpty(mPathPrefix);
    }

    /** Sets the path separator */
    void setPathSeparator(String pathSeparator) {
        mPathSeparator = pathSeparator;
    }

    /** Sets the prefix */
    void setPathPrefix(String pathPrefix) {
        mPathPrefix = pathPrefix;
    }

    /** Gets the context for this store */
    Context getContext() {
        return mContext;
    }

    /** Returns a clone of the transport associated with this store. */
    MailTransport cloneTransport() {
        return mTransport.clone();
    }

    /**
     * Fixes the path prefix, if necessary. The path prefix must always end with the
     * path separator.
     */
    void ensurePrefixIsValid() {
        // Make sure the path prefix ends with the path separator
        if (!TextUtils.isEmpty(mPathPrefix) && !TextUtils.isEmpty(mPathSeparator)) {
            if (!mPathPrefix.endsWith(mPathSeparator)) {
                mPathPrefix = mPathPrefix + mPathSeparator;
            }
        }
    }

    /**
     * Gets a connection if one is available from the pool, or creates a new one if not.
     */
    ImapConnection getConnection() {
        ImapConnection connection = null;
        while ((connection = mConnectionPool.poll()) != null) {
            try {
                connection.setStore(this, mUsername, mPassword);
                connection.executeSimpleCommand(ImapConstants.NOOP);
                break;
            } catch (MessagingException e) {
                // Fall through
            } catch (IOException e) {
                // Fall through
            }
            connection.close();
            connection = null;
        }
        if (connection == null) {
            connection = new ImapConnection(this, mUsername, mPassword);
        }
        return connection;
    }

    /**
     * Save a {@link ImapConnection} in the pool for reuse. Any responses associated with the
     * connection are destroyed before adding the connection to the pool.
     */
    void poolConnection(ImapConnection connection) {
        if (connection != null) {
            connection.destroyResponses();
            mConnectionPool.add(connection);
        }
    }

    /**
     * Prepends the folder name with the given prefix and UTF-7 encodes it.
     */
    static String encodeFolderName(String name, String prefix) {
        // do NOT add the prefix to the special name "INBOX"
        if (ImapConstants.INBOX.equalsIgnoreCase(name)) return name;

        // Prepend prefix
        if (prefix != null) {
            name = prefix + name;
        }

        // TODO bypass the conversion if name doesn't have special char.
        ByteBuffer bb = MODIFIED_UTF_7_CHARSET.encode(name);
        byte[] b = new byte[bb.limit()];
        bb.get(b);

        return Utility.fromAscii(b);
    }

    /**
     * UTF-7 decodes the folder name and removes the given path prefix.
     */
    static String decodeFolderName(String name, String prefix) {
        // TODO bypass the conversion if name doesn't have special char.
        String folder;
        folder = MODIFIED_UTF_7_CHARSET.decode(ByteBuffer.wrap(Utility.toAscii(name))).toString();
        if ((prefix != null) && folder.startsWith(prefix)) {
            folder = folder.substring(prefix.length());
        }
        return folder;
    }

    /**
     * Returns UIDs of Messages joined with "," as the separator.
     */
    static String joinMessageUids(Message[] messages) {
        StringBuilder sb = new StringBuilder();
        boolean notFirst = false;
        for (Message m : messages) {
            if (notFirst) {
                sb.append(',');
            }
            sb.append(m.getUid());
            notFirst = true;
        }
        return sb.toString();
    }

    static class ImapMessage extends MimeMessage {
        ImapMessage(String uid, ImapFolder folder) {
            mUid = uid;
            mFolder = folder;
        }

        public void setSize(int size) {
            mSize = size;
        }

        @Override
        public void parse(InputStream in) throws IOException, MessagingException {
            super.parse(in);
        }
		//paul add start
        @Override
        public void parse(InputStream in, String header) throws IOException, MessagingException {
            super.parse(in, header);
        }
		//paul add end
        public void setFlagInternal(Flag flag, boolean set) throws MessagingException {
            super.setFlag(flag, set);
        }

        @Override
        public void setFlag(Flag flag, boolean set) throws MessagingException {
            super.setFlag(flag, set);
            mFolder.setFlags(new Message[] { this }, new Flag[] { flag }, set);
        }
    }

    static class ImapException extends MessagingException {
        private static final long serialVersionUID = 1L;

        String mAlertText;

        public ImapException(String message, String alertText, Throwable throwable) {
            super(message, throwable);
            mAlertText = alertText;
        }

        public ImapException(String message, String alertText) {
            super(message);
            mAlertText = alertText;
        }

        public String getAlertText() {
            return mAlertText;
        }

        public void setAlertText(String alertText) {
            mAlertText = alertText;
        }
    }
    
    //Aurora <shihao> <2014-11-14> for imap folder Merge local_folder begin
    @Override
    public HashMap<Integer, String> getNamesMap() throws MessagingException{
    	imapFolderNameMap.clear();
        ImapConnection connection = getConnection();
        try {
            connection.executeSimpleCommand(ImapConstants.NOOP);
            String imapCommand = ImapConstants.LIST + " \"\" \"*\"";
            if (mPathPrefix != null) {
                imapCommand = ImapConstants.LIST + " \"\" \"" + mPathPrefix + "*\"";
            }
            List<ImapResponse> responses = connection.executeSimpleCommand(imapCommand);
            for (ImapResponse response : responses) {
                // S: * LIST (\Noselect) "/" ~/Mail/foo
                if (response.isDataResponse(0, ImapConstants.LIST)) {
                    // Get folder name.
                    ImapString encodedFolder = response.getStringOrEmpty(3);
                    if (encodedFolder.isEmpty()) continue;

                    String folderName = decodeFolderName(encodedFolder.getString(), mPathPrefix);

                    //Aurora <shihao> <2014-11-14> for store ImapFolder;
                    int maiboxType = LegacyConversions.getImapFolderType(mContext, folderName);
 //                   LogUtils.w("shioho","ImapStore folderName=="+folderName);
 //                   LogUtils.w("shioho","ImapStore type=="+maiboxType);
                    switch (maiboxType) {
					case Mailbox.TYPE_SENT:
					case Mailbox.TYPE_TRASH:
					case Mailbox.TYPE_DRAFTS:
	                    imapFolderNameMap.put(maiboxType, folderName);
						break;
					default:
						break;
					}                
                }
            }
        } catch (IOException ioe) {
            connection.close();
            throw new MessagingException("Unable to get folder list.", ioe);
        } catch (AuthenticationFailedException afe) {
            // We do NOT want this connection pooled, or we will continue to send NOOP and SELECT
            // commands to the server
            connection.destroyResponses();
            connection = null;
            throw afe;
        } finally {
            if (connection != null) {
                poolConnection(connection);
            }
        }
    	return imapFolderNameMap;
    }
    //Aurora <shihao> <2014-11-14> for imap folder Merge local_folder end
}