/*
 * Copyright 2001 Stewart Allen <stewart@neuron.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package com.neuron.app.scramble;

// ---( imports )---
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.net.SocketException;
import java.util.StringTokenizer;

public class Link 
{
	// ---( static fields )---
	private final static byte[] XDOM = (
		"<?xml version=\"1.0\"?>"+
		"<!DOCTYPE cross-domain-policy SYSTEM \"http://www.adobe.com/xml/dtds/cross-domain-policy.dtd\">"+
		"<cross-domain-policy>"+
		"<site-control permitted-cross-domain-policies=\"master-only\"/>"+
		"<allow-access-from domain=\"localhost\" to-ports=\"1234-1236\"/>"+
		"<allow-access-from domain=\"mrallen.com\" to-ports=\"1234-1236\"/>"+
		"</cross-domain-policy>\n\0\0\0").getBytes();

	// ---( static methods )---
	public void log(String msg) {
		System.out.println(cname+" :: "+msg);
	}
	
	public static String munge(String str)
	{
		return str.replace(' ','\0');
	}

	public static String unmunge(String str)
	{
		return str.replace('\0',' ');
	}

	public static String toString(String args[])
	{
		return toString(args, 0);
	}

	public static String toString(String args[], int start)
	{
		StringBuffer sb = new StringBuffer();
		for (int i=start; i<args.length; i++)
		{
			if (i > start)
			{
				sb.append(" ");
			}
			sb.append(args[i]);
		}
		return sb.toString();
	}

	// ---( constructors )---
	public Link ()
	{
		cname = getClass().toString();
		int pos = cname.lastIndexOf('.');
		if (pos > 0) {
			cname = cname.substring(Math.max(cname.lastIndexOf('$', pos),pos)+1);
		}
	}

	// ---( instance fields )---
	private String cname;
	private String lname;
	private Socket link;
	private volatile boolean running;
	private long lastRecv;
	private InputStream in;
	private OutputStream out;

	// ---( instance methods )---
	public String toString() {
		return "Link["+lname+","+(System.currentTimeMillis()-lastRecv)+","+running+"]";
	}
	
	public Socket getSocket()
	{
		return link;
	}

	private boolean isRunning()
	{
		return running;
	}

	public long getFreshness() {
		return System.currentTimeMillis() - lastRecv;
	}
	
	public void setFresh() {
		lastRecv = System.currentTimeMillis();
	}
	
	public synchronized final void setup(Socket sock)
		throws IOException, InterruptedException
	{
		if (running == true) {
			throw new IOException("already running");
		}
		link = sock;
		lname = sock.getRemoteSocketAddress().toString();
		in = sock.getInputStream();
		out = sock.getOutputStream();
		new Thread() {
			public void run() {
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				try {
					outer: while (true) {
						int ch = 0;
						while ( (ch = in.read()) >= 0 ) {
							switch (ch) {
								case '<':
									if (buf.size() == 0) {
						    			log("this looks like a crossdomain request - permitting all");
						    			out.write(XDOM);
						    			out.flush();
						    			buf.reset();
						    			break outer;
									} else {
										buf.write(ch);
									}
								case '\r':
									break;
								case '\n':
									recvData(buf.toByteArray(), 0, buf.size());
									buf.reset();
									break;
								default:
									buf.write(ch);
									break;
							}
						}
					}
				} catch (SocketException sex) {
					log("socket exception "+sex+" on "+lname);
				} catch (IOException iex) {
					log("io exception "+iex+" on "+lname);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
				try {
					linkDown();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}.start();		
		running = true;
		setFresh();
	}

	public void closeLink()
	{
		running = false;
		try { link.close(); } catch (Exception ex) { }
	}

	public void linkDown(boolean onError)
	{
		closeLink();
	}

	private final static Class handleSig[] = new Class[] { String[].class };

	private final void dispatch(String args[])
	{
		if (args.length == 0)
		{
			return;
		}
		try
		{
			String sarg[] = new String[args.length-1];
			System.arraycopy(args,1,sarg,0,sarg.length);
			getClass().getMethod("CMD_"+args[0], handleSig).invoke(this, new Object[] { sarg });
		}
		catch (InvocationTargetException ex)
		{
			System.err.println(getClass()+" invoke error on CMD '"+args[0]+"'");
			ex.printStackTrace();
			handleCommand(args);
		}
		catch (IllegalAccessException ex)
		{
			System.err.println(getClass()+" access error on CMD '"+args[0]+"'");
			ex.printStackTrace();
			handleCommand(args);
		}
		catch (NoSuchMethodException ex)
		{
			System.err.println(getClass()+" does not implement CMD '"+args[0]+"'");
			handleCommand(args);
		}
	}

	public void handleCommand(String args[])
	{
		log("handleCommand : "+args[0]+" : "+args.length);
	}

	public final void sendCommand(String cmd)
	{
		sendCommand(cmd, new String[0]);
	}

	public final void sendCommand(String cmd, String msg)
	{
		sendCommand(cmd, new String[] { msg });
	}

	public final void sendCommand(String cmd, String args[])
	{
//		log("sendCommand : "+cmd+" : "+args.length);
		if (!running)
		{
			return;
		}
		try {
			new Command(cmd,args).send();
		} catch (IOException ex) {
			log("link down : "+lname+" : "+ex);
			linkDown(true);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	// ---( interface methods )---
	public void recvData(byte data[], int off, int len) {
//		dump("recvData", data);
		setFresh();
		try {
			String cmd = new String(data, off, len, "UTF8");
			StringTokenizer st = new StringTokenizer(cmd," ");
			String args[] = new String[st.countTokens()];
			for (int i=0; st.hasMoreTokens(); i++)
			{
				args[i] = unmunge(st.nextToken());
			}
			dispatch(args);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
	public void linkDown() {
		linkDown(false);
	}

	private void dump(String tag, byte data[]) {
		StringBuffer s = new StringBuffer();
		for (int i=0; i<data.length; i++) {
			if (i > 0) {
				s.append(" ");
			}
			int b = data[i];
			if (b >= 32 && b <= 126) {
				s.append((char)b);
			} else {
				s.append("(");
				s.append(Integer.toString(b));
				s.append(")");
			}
		}
		log(tag+" [ "+s+" ]");
	}
	
	// ---( INNER CLASS Command )---
	private class Command
	{
		private String cmd;
		private String args[];

		Command(String cmd, String args[])
		{
			this.cmd = cmd;
			this.args = args;
		}

		public String toString() {
			StringBuffer sb = new StringBuffer(munge(cmd));
			for (int i=0; i<args.length; i++)
			{
				sb.append(" ");
				sb.append(munge(args[i]));
			}
			sb.append("\n");
			return sb.toString();
		}
		
		public void send()
			throws IOException, InterruptedException
		{
			out.write(toString().getBytes("UTF8"));
			out.flush();
		}
	}
}
