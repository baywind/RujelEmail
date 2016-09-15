// Mailer.java

/*
 * Copyright (c) 2008, Gennady & Michael Kushnir
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 * 
 * 	•	Redistributions of source code must retain the above copyright notice, this
 * 		list of conditions and the following disclaimer.
 * 	•	Redistributions in binary form must reproduce the above copyright notice,
 * 		this list of conditions and the following disclaimer in the documentation
 * 		and/or other materials provided with the distribution.
 * 	•	Neither the name of the RUJEL nor the names of its contributors may be used
 * 		to endorse or promote products derived from this software without specific 
 * 		prior written permission.
 * 		
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
 * WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.rujel.email;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.*;
import javax.mail.internet.*;

import com.sun.mail.smtp.SMTPTransport;
import com.webobjects.appserver.*;
import com.webobjects.eoaccess.EOUtilities.MoreThanOneException;
import com.webobjects.foundation.*;

import net.rujel.reusables.*;

public class Mailer {
	
	protected static final Logger logger = Logger.getLogger("rujel.mail");
	public static final DateFormat filenameFormat = new SimpleDateFormat("yyMMdd_HHmmss.SSS'.eml'");

	protected SettingsReader settings = SettingsReader.settingsForPath("mail", false);
	protected boolean dontSend = settings.getBoolean("dontSend", false);
//	protected boolean writeToFile = settings.getBoolean("writeToFile",false);
	private String prot = "smtp";
	private String mailhost = settings.get("smtpServerURL", null);
	private String user = settings.get("smtpUser", null);
	public final NSMutableDictionary extraHeaders = new NSMutableDictionary();
	public File outbox;
	protected File mailDir;
		
	protected Session mailSession;
	
	
	public Mailer() {
		if(!dontSend) {
			outbox = outbox();
			java.security.Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
			
//			http://java.sun.com/products/javamail/javadocs/com/sun/mail/smtp/package-summary.html
			String tmp = settings.get("propertiesFile", null);
			Properties props = new Properties();
			if(tmp != null) {
				try {
					FileInputStream in = new FileInputStream(Various.convertFilePath(tmp));
					props.load(in);
				} catch (Exception e) {
					logger.log(WOLogLevel.WARNING,"Error loading initial properties file",e);
				}
			}
			if(settings.getBoolean("secure", false))
				prot = "smtps";
			if(mailhost != null)
				props.put("mail." + prot + ".host", mailhost);
			tmp = settings.get("smtpPort", null);
			if(tmp != null)
				props.put("mail." + prot + ".port", tmp);
		    if (user != null)
		    	props.put("mail." + prot + ".auth", "true");
		    
		    mailSession = Session.getInstance(props, null);
		    logger.finer("Constructed mailer");
		}
		if(settings.getBoolean("writeToFile",false)) {
			String mailDirPath = SettingsReader.stringForKeyPath("mail.writeFileDir", null);
			if(mailDirPath == null) {
				logger.log(WOLogLevel.WARNING,
						"Can not write to file because writeFileDir is not specified");
			} else {
				mailDirPath = Various.convertFilePath(mailDirPath);
				mailDir = new File(mailDirPath);
				if(!mailDir.exists() || !mailDir.canWrite()) {
					logger.log(WOLogLevel.WARNING,
							"Preferred writeFileDir does not exist or is not writable");
					mailDir = null;
				} else if (mailDir.equals(outbox)) {
					logger.log(WOLogLevel.WARNING,
							"Preferred writeFileDir can not be the same as outbox.");
					mailDir = null;
				}
			}
		}
	}
	
	protected void finalize() throws Throwable {
		super.finalize();
	}
	
	protected HeadersDelegate headersDelegate;
	public void setHeadersDelegate(HeadersDelegate delegate) {
		headersDelegate = delegate;
	}

	protected MimeMessage constructMessage (InternetAddress[] to) throws MessagingException {
		MimeMessage msg = new MimeMessage(mailSession);
		String adr = settings.get("mailFrom", null);
		if(headersDelegate != null)
			adr = headersDelegate.getHeader("from",adr);
		if (adr != null)
			msg.setFrom(new InternetAddress(adr));
		else
			msg.setFrom();

		msg.setRecipients(Message.RecipientType.TO, to);
		
		adr = settings.get("replyTo", null);
		if(headersDelegate != null)
			adr = headersDelegate.getHeader("reply-to",adr);
		if(adr != null)
			msg.setReplyTo(InternetAddress.parse(adr, false));
			
		adr = settings.get("cc", null);
		if(headersDelegate != null)
			adr = headersDelegate.getHeader("cc",adr);
		if(adr != null)
			msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse(adr, false));
		adr = settings.get("bcc", null);
		if(headersDelegate != null)
			adr = headersDelegate.getHeader("bcc",adr);
		if(adr != null)
			msg.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(adr, false));
		msg.setHeader("X-Mailer", "RUJEL");
		msg.setSentDate(new Date());			
		if(extraHeaders.count() > 0) {
			Enumeration enu = extraHeaders.keyEnumerator();
			while (enu.hasMoreElements()) {
				String key = (String) enu.nextElement();
				msg.setHeader(key, extraHeaders.valueForKey(key).toString());
			}
		}
		if(headersDelegate != null) {
			String[] headers = headersDelegate.headers();
			if(headers == null || headers.length == 0)
				return msg;
			for (int i = 0; i < headers.length; i++) {
				if(headers[i].equalsIgnoreCase("to") || headers[i].equalsIgnoreCase("from")
						|| headers[i].equalsIgnoreCase("cc") || headers[i].equalsIgnoreCase("bcc"))
					continue;
				String value = headersDelegate.getHeader(headers[i]);
				if(value != null)
					msg.setHeader(headers[i], value);
			}
		}
		return msg;
	}

	protected void sendMessage(Message msg) throws MessagingException {
		sendMessage(msg, (outbox != null));
	}
	protected void sendMessage(Message msg, boolean writeToOutbox) throws MessagingException {
		SMTPTransport t = (SMTPTransport)mailSession.getTransport(prot);
		try {
			if (user != null)
				t.connect(mailhost, user, settings.get("smtpPassword", null));
			else
				t.connect();
			t.sendMessage(msg, msg.getAllRecipients());
		} catch (MessagingException ex) {
			if(writeToOutbox) {
				File out = writeToFile(msg, outbox);
				if(out != null)
					logger.log(WOLogLevel.FINE,
							"Unsent message written to file: " + out.getName());
			}
			throw ex;
		} finally {
			if (mailSession.getDebug())
				logger.log(WOLogLevel.FINE,"SMTP responded:",t.getLastServerResponse());
			t.close();
		}
	}

	public static File writeToFile(Message msg, File directory) {
		File file;
		do {
			String filename = filenameFormat.format(new Date());
			file = new File(directory, filename);
		} while (file.exists());
		try {
			FileOutputStream fos = new FileOutputStream(file);
			msg.writeTo(fos);
			fos.close();
			return file;
		} catch (Exception e) {
			logger.log(WOLogLevel.WARNING,"Failed writing message to file: " + file.getName(), e);
			return null;
		}
	}

	public void sendTextMessage(String subject, String text, InternetAddress[] to)
															throws MessagingException{
		sendMessage(subject, text, to, null, null);
	}
	public void sendMessage(String subject, String text, InternetAddress[] to, 
				NSData attachment, String attachName) throws MessagingException{
			//try {
				MimeMessage msg = constructMessage(to);
				msg.setSubject(subject,"UTF-8");
				if(attachment == null) {
					msg.setText(text,"UTF-8");
				} else {
					MimeBodyPart mbp1 = new MimeBodyPart();
					if(text == null)
						text = defaultMessageText();
					mbp1.setText(text,"UTF-8");
					//mbp1.setDataHandler(new DataHandler(text, "text/plain; charset=\"UTF-8\""));
					StringBuilder mime = new StringBuilder();
					if(attachName == null) {
						attachName = "file";
						mime.append("application/octet-stream");
					} else {
						mime.append(WOApplication.application().resourceManager()
								.contentTypeForResourceNamed(attachName));
					}
					MimeBodyPart mbp2 = new MimeBodyPart();
					DataSource ds = new NSDataSource(attachName,attachment);
				    mbp2.setDataHandler(new DataHandler(ds));
				    mbp2.setFileName(attachName);
				    mime.append("; name=\"").append(attachName).append('"');
					mbp2.setHeader("Content-Type", mime.toString());
				    Multipart mp = new MimeMultipart();
				    mp.addBodyPart(mbp1);
				    mp.addBodyPart(mbp2);

				    // add the Multipart to the message
				    msg.setContent(mp);

				}
		if(mailDir != null) {
			File out = writeToFile(msg, mailDir);
			if(out != null)
				logger.log(WOLogLevel.FINER,"Message written to file: " + out.getName(),subject);
		}
		if(!dontSend) {
				sendMessage(msg);
				logger.log(WOLogLevel.FINER,"Message was sent",subject);
		}
	}
	
	public static File outbox() {
		String outboxDir = SettingsReader.stringForKeyPath("mail.outboxDir", null);
		if(outboxDir == null)
			return null;
		outboxDir = Various.convertFilePath(outboxDir);
		File outbox = new File(outboxDir);
		if(outbox.isDirectory() && outbox.canWrite())
			return outbox;
		else
			return null;
	}
	
	public static boolean hasDelayed() {
		File outbox = outbox();
		if(outbox == null)
			return false;
		File[] delayedList = outbox.listFiles(filenameFilter);
		return (delayedList != null && delayedList.length > 0);
	}
	
	public static File getFirstDelayed(File[] list) {
		if(list == null || list.length == 0)
			return null;
		File minFile = null;
		String minName = null;
		for (int i = 0; i < list.length; i++) {
			if(list[i] == null)
				continue;
			if(minName == null || list[i].getName().compareToIgnoreCase(minName) < 0) {
				minFile = list[i];
				minName = minFile.getName();
			}
		}
		return minFile;
	}

	public static final FilenameFilter filenameFilter = new FilenameFilter() {
		
		public boolean accept(File dir, String name) {
			return name.endsWith(".eml");
		}
	};
	
	public MimeMessage sendFromFile(File file) throws IOException, MessagingException {
		if(outbox == null)
			return null;
		InputStream in = new FileInputStream(file);
		MimeMessage msg = new MimeMessage(mailSession, in);
		in.close();
		sendMessage(msg, false);
		return msg;
	}
	
	public int sendMinDelayed(File[] delayedList) {
		if(delayedList == null || delayedList.length == 0) {
			delayedList = null;
			return -1;
		}
		int delayedIdx = -1;
		File delayed = null;
		String minName = null;
		for (int i = 0; i < delayedList.length; i++) {
			if(delayedList[i] == null)
				continue;
			if(!delayedList[i].exists()) {
				delayedList[i] = null;
				continue;
			}
			if(minName == null || delayedList[i].getName().compareToIgnoreCase(minName) < 0) {
				delayedIdx = i;
				delayed = delayedList[delayedIdx];
				minName = delayed.getName();
			}
		}		
		if(delayed == null) {
			delayedList = null;
			return delayedIdx;
		}
		try {
			sendFromFile(delayed);
			logger.log(WOLogLevel.FINE, "Succusfully resent delayed email: " + minName);
			delayed.delete();
		} catch (Exception e) {
			if(e instanceof IOException || e instanceof ParseException) {
				String newName = minName + ".err";
				logger.log(WOLogLevel.WARNING,"Failed to parce delayed email "
						+ minName + " renaming to " + newName,e);
				delayed.renameTo(new File(outbox,newName));
				delayedList[delayedIdx] = null;
				return sendMinDelayed(delayedList);
			} else {
				String newName = Mailer.filenameFormat.format(new Date());

				logger.log(WOLogLevel.WARNING,"Failed to resend delayed email "
						+ minName + " renaming to " + newName,e);
				delayed.renameTo(new File(outbox,newName));
			}
		}
		return delayedIdx;
	}
	
	public static NSData zip(NSData content,String filename) throws IOException {
		ByteArrayOutputStream zipped = new ByteArrayOutputStream(content.length());
		ZipOutputStream zipStream = new ZipOutputStream(zipped);
		zipStream.putNextEntry(new ZipEntry(filename));
		content.writeToStream(zipStream);
		zipStream.closeEntry();
		zipStream.close();
		content = new NSData(zipped.toByteArray());
		return content;
	}
	
	public void sendPage(String subject, String text, WOActionResults page,
			InternetAddress[] to, boolean zip) throws MessagingException{
		NSData content = page.generateResponse().content();
		
		WOSession ses = null;
		if(page instanceof WOComponent) {
			WOComponent cpage = (WOComponent)page;
			if(cpage.context().hasSession())
				ses = cpage.session();
			//name.append(cpage.name());
		}
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
		String name = sdf.format(new Date());
		if(zip) {
			try {
				content = zip(content,name + ".html");
			} catch (Exception e) {
				logger.log(WOLogLevel.WARNING,"Error zipping message",
						new Object[] {ses,subject,e});
			}
		}
		sendMessage(subject, text, to, content, name + ((zip)?".zip":".html"));
		/*
		if(!dontSend) {
				MimeMessage msg = constructMessage(to);
				msg.setSubject(subject,"UTF-8");

				MimeBodyPart mbp1 = new MimeBodyPart();
				if(text == null)
					text = defaultMessageText();
				mbp1.setText(text,"UTF-8");
				//mbp1.setDataHandler(new DataHandler(text, "text/plain; charset=\"UTF-8\""));

				MimeBodyPart mbp2 = new MimeBodyPart();
				DataSource ds = new NSDataSource(name.toString(),content);
			    mbp2.setDataHandler(new DataHandler(ds));
			    mbp2.setFileName(name.toString());
				mbp2.setHeader("Content-Type", "text/html; name=\"" + name + '"');
			    Multipart mp = new MimeMultipart();
			    mp.addBodyPart(mbp1);
			    mp.addBodyPart(mbp2);

			    // add the Multipart to the message
			    msg.setContent(mp);

			    sendMessage(msg);
				Object[] args = new Object[] {ses,subject};
				logger.log(WOLogLevel.FINER,"Message was sent",args);
		}
		if(writeToFile) {
			writeToFile(subject + ((zip)?".zip":".html"), content);
		}*/
	}

	protected String _defaultMessage;
	public String defaultMessageText() {
		if(_defaultMessage == null) {
			_defaultMessage = "";
			/*(String)WOApplication.application().valueForKeyPath(
			"strings.RujelContacts_Contacts.defaultMessage");*/
			String filePath = settings.get("messageFilePath", null);
			if(filePath != null) {
				filePath = Various.convertFilePath(filePath);
				File file = new File(filePath);
				try {
					if(file.length() <= 0)
						return _defaultMessage;
					InputStream strm = new FileInputStream(file);
					InputStreamReader reader = new InputStreamReader(strm,"utf8");
					int size = strm.available();
					char[] cbuf = new char[size];
					size = reader.read(cbuf, 0, size);
					_defaultMessage = new String(cbuf,0,size);
					reader.close();
					strm.close();
				} catch (IOException e) {
					logger.log(WOLogLevel.WARNING,
							"Error reading default message from file " + filePath,e);
				}
			}
		}
		return _defaultMessage;
	}

	protected static boolean writeToFile(String filename, NSData message) {
		String mailDir = SettingsReader.stringForKeyPath("mail.writeFileDir", null);
		if(mailDir != null) {
			mailDir = Various.convertFilePath(mailDir);
			try {
				File messageFile = new File(mailDir,filename);
				FileOutputStream fos = new FileOutputStream(messageFile);
				message.writeToStream(fos);
				fos.close();
				logger.log(WOLogLevel.FINER,"Mail written to file: " + filename);
				return true;
			} catch (Exception ex) {
				logger.log(WOLogLevel.WARNING,"Failed to write result for "
						+ filename,new Object[] {ex});
				return false;
			}
		} else {
			logger.log(WOLogLevel.WARNING,"Could not write file '" + filename +
					"' because target directory not specified");
			return false;
		}
	}
	
	public static InternetAddress[] coerceAddress(String address, boolean strict, boolean single) 
			throws AddressException, MoreThanOneException {
		InternetAddress[] parced = InternetAddress.parse(address, strict);
		if(single && parced.length > 1)
			throw new MoreThanOneException("Multiple (" + parced.length + ") adresses found");
		for (int i = 0; i < parced.length; i++) {
			String adr = parced[i].getAddress();
			if(adr == null || adr.indexOf('@') < 0)
				throw new AddressException("Domain name is required");
		}
		return parced;
	}

	protected static class NSDataSource implements DataSource {
		private NSData data;
		private String name;
		private String contentType = "text/html";
		
		public NSDataSource(String aName, NSData content) {
			name = aName;
			data = content;
		}
		
		public java.lang.String getName() {
			return name;
		}
		
		public java.io.InputStream getInputStream() throws IOException {
			return data.stream();
		}

        public java.io.OutputStream getOutputStream() throws IOException {
        	throw new UnsupportedOperationException("Output not supported");
        }
        
        public java.lang.String getContentType() {
        	return contentType;
        }
        
        public void setContentType(String newContentType) {
        	contentType = newContentType;
        }
	}
	
	public static interface HeadersDelegate {
		public String[] headers();
		public boolean forceHeader(String name);
		public String getHeader(String name);
		public String getHeader(String name, String ifNone);
	}
}
