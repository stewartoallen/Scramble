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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * TODO:
 *
 * - BOT: try more than one iteration of possibilities (ss.words) if more advanced
 * - BOT: leaving during endgame does not terminate play properly? -- test
 * - add permutations of word/word to findWord check list
 * - verify that multi-word steal matches takes longest to shortest
 * - sometimes says not in pot when it is - perhaps b/c below
 * -   also intermittent bug w/ player leaving and words still around
 * - logged on player list on main page right of chat
 * - add spectator state buttons - how?
 * - game getting stuck where all new players are spectators? -- test
 * - race condition w/ lose/steal/take??? lead to wrong count
 */
public class Scramble
{
	// ---( static fields )---
	public final static double VERSION = 1.66;

	private final static int GameExpireWarning = 120000;
	private final static int GameExpireTime = 180000;

	private final static int WAITING = 0;
	private final static int READY   = 1;
	private final static int INPLAY  = 2;

	private final static int BOT_LEVEL1  = 1;
	private final static int BOT_LEVEL2  = 2;
	private final static int BOT_LEVEL3  = 3;
	
	private final static boolean startServer = System.getProperty("server","1").equals("1");
	private final static boolean startBots = System.getProperty("bots","1").equals("1");
	private final static String gameHost = System.getProperty("host","localhost");

	private final static String BEGINNER_GAME_1 = "Beginner Training 1";
	private final static String BEGINNER_GAME_2 = "Beginner Training 2";
	private final static String INTERMEDIATE_GAME = "Intermediate Training";
	private final static String ADVANCED_GAME = "Advanced Training";
	
	private final static byte base = 'a';
	private final static int  maxword = 25;
	private final static long endGamePeriod[] = { 40000, 30000, 20000, 15000 };
	private final static long gracePeriod[]   = { 3000,  2500,  2000,  1750  };
	private final static long dealSpeed[]     = { 4000,  3000,  2000,  1500  };

	private final static SimpleDateFormat format = new SimpleDateFormat ("yyMMdd-hhmmss");
	private final static Random random = new Random(System.currentTimeMillis());

	// ---( static methods )---
	public static void main(String args[])
		throws Exception
	{
		Scramble s = new Scramble(args.length < 1 ? 1234 : Integer.parseInt(args[0]));
		/*
		s.getWords("teh").printWords();
		s.getWords("lift").printWords();
		s.getWords("wrap").printWords();
		s.getWords("ample").printWords();
		s.getWords("pittance").printWords();
		s.getWords("littering").printWords();
		*/
		s.startServer();
		/*
		 * start jetty
		 */
		/*
		Handler handler=new AbstractHandler()
		{
		    public void handle(String target, HttpServletRequest request, HttpServletResponse response, int dispatch) 
		        throws IOException, ServletException
		    {
		        response.setContentType("text/html");
		        response.setStatus(HttpServletResponse.SC_OK);
		        response.getWriter().println("<h1>Hello</h1>");
		        ((Request)request).setHandled(true);
		    }
		};
		Server server = new Server(8080);
		server.setHandler(handler);
		server.start();
		*/
	}

	public final static String genUID() {
		return Long.toString(Math.abs(random.nextLong()),26)+Long.toString(Math.abs(random.nextLong()),26);
	}
	
	// ---( constructors )---
	public Scramble (int port)
		throws Exception
	{
		debug("Scramble Server v"+VERSION);
		sport = port;
		comp = new WordComp();
		dict = new HashMap();
		WordSource twl = new WordSource("/res/words/us-english-twl", true);
		WordSource sowpods = new WordSource("/res/words/uk-english-sowpods", true);
		WordSource ods = new WordSource("/res/words/french-ods", false);
		WordSource zingarelli = new WordSource("/res/words/italian-zingarelli", false);
		dict.put("twl", twl);
		dict.put("ods", ods);
		dict.put("default", twl);
		dict.put("sowpods", sowpods);
		dict.put("zingarelli", zingarelli);
		dict.put("us english", twl);
		dict.put("uk english", sowpods);
		dict.put("french", ods);
		dict.put("italian", zingarelli);
		if (startServer) {
			File dataDir = new File("data/"+sport);
			dataDir.mkdirs();
		}
	}

	public void debug(String msg)
	{
		System.out.println(format.format(new Date()).concat(" | ").concat(msg));
	}

	// ---( instance fields )---
	private int sport;
	private long time;
	private WordComp comp;
	private MetaServer server;
	private HashMap dict;

	// ---( instance methods )---
	private void startTimer()
	{
		time = System.currentTimeMillis();
	}

	private long checkTimer()
	{
		return System.currentTimeMillis()-time;
	}

	public void startServer()
	{
		if (server == null)
		{
			server = new MetaServer();
			server.start();
		}
	}

	// ---( interface methods )---

	// ----------------------------------------------------------------------
	// ---( INNER CLASS WordSource )---
	// ----------------------------------------------------------------------
	private class WordSource {
		private LetterSpace lists[];
		private byte words[][];
		private int index;
		private String resource;
		private boolean noPlurals;
		
		public String toString() {
			return "Words("+resource+")";
		}
		
		public boolean noPlurals() {
			return noPlurals;
		}
		
		WordSource(String res, boolean noplurals) throws IOException {
			noPlurals = noplurals;
			resource = res;
			lists = new LetterSpace[26];
			words = new byte[1000][];
			for (int i=0; i<26; i++)
			{
				lists[i] = new LetterSpace(i);
			}
			byte word[] = new byte[100];
			byte buf[] = new byte[1024];
			int blen = 0;
			int wlen = 0;
			InputStream in = getClass().getResourceAsStream(res);
			if (in == null)
			{
				throw new RuntimeException("missing words database "+res);
			}
			startTimer();
			while ( (blen = in.read(buf)) >= 0 )
			{
				for (int pos=0; pos<blen; pos++)
				{
					switch (buf[pos])
					{
						case '\r':
							break;
						case '\n':
							byte save[] = new byte[wlen];
							System.arraycopy(word,0,save,0,wlen);
							addWord(save);
							for (int i=0; i<26; i++)
							{
								int repeat = 0;
								for (int j=0; j<wlen; j++)
								{
									if (save[j] - base == i)
									{
										repeat++;
									}
								}
								if (repeat > 0)
								{
									lists[i].addWord(wlen,index-1,repeat);
								}
							}
							wlen = 0;
							break;
						default:
							word[wlen++] = buf[pos];
							break;
					}
				}
			}
			debug("loaded dictionary in "+checkTimer()+"ms");
		}
		
		/**
		 * Given a set of letters (word), return all valid word
		 * permutations <b>of the same length</b> in the dictionary.
		 *
		 * @param word word or set of letters
		 */
		public SolutionSet getWords(String word)
		{
			return getWords(word.getBytes());
		}

		public SolutionSet getWords(byte word[])
		{
			return new SolutionSet(this, word);
		}

		public byte[] getWord(int index)
		{
			return words[index];
		}

		public String getWordString(int index)
		{
			return new String(words[index]);
		}

		private void addWord(byte word[])
		{
			words[index++] = word;
			if (index >= words.length)
			{
				byte nwords[][] = new byte[words.length+1000][];
				System.arraycopy(words,0,nwords,0,words.length);
				words = nwords;
			}
		}
	}

	// ----------------------------------------------------------------------
	// ---( INNER CLASS LetterSpace )---
	// ----------------------------------------------------------------------
	private class LetterSpace
	{
		private int letter;
		private int words[][];
		private int ptr[];

		public LetterSpace(int letter)
		{
			this.letter = letter;
			this.words = new int[maxword][1000];
			this.ptr = new int[maxword];
			for (int i=0; i<maxword; i++)
			{
				ptr[i] = 0;
			}
		}

		public void addWord(int len, int index, int repeat)
		{
			words[len][ptr[len]++] = (repeat << 24) | index;
			if (ptr[len] >= words[len].length)
			{
				int nwords[] = new int[words[len].length+1000];
				System.arraycopy(words[len],0,nwords,0,words[len].length);
				words[len] = nwords;
			}
		}

		public void addMatch(SolutionSet set, int wlen, int repeat)
		{
			for (int i=0; i<ptr[wlen]; i++)
			{
				if ( (words[wlen][i] >> 24) == repeat )
				{
					set.addWord(words[wlen][i] & 0xffffff);
				}
			}
		}

		public boolean contains(int wlen, int index, int repeat)
		{
			for (int i=0; i<ptr[wlen]; i++)
			{
				if ( (words[wlen][i] >> 24) == repeat &&
					(words[wlen][i] & 0xffffff) == index )
				{
					return true;
				}
			}
			return false;
		}

		public int getLength(int wlen)
		{
			return ptr[wlen];
		}
	}

	// ----------------------------------------------------------------------
	// ---( INNER CLASS SolutionSet )---
	// ----------------------------------------------------------------------
	private class SolutionSet
	{
		private WordSource source;
		private byte word[];
		private int letters[] = new int[26];
		private int match[] = new int[1000];
		private int matchlen;
		private int found;

		public SolutionSet(WordSource source, byte word[])
		{
			this.source = source;
			setWord(word);
		}

		public SolutionSet(WordSource source, String word)
		{
			this(source, word.getBytes());
		}

		public void setWord(byte word[])
		{
			try {
				
			this.found = 0;
			this.word = word;
			startTimer();
			int wlen = word.length;
			for (int i=0; i<26; i++)
			{
				letters[i] = 0;
			}
			for (int i=0; i<wlen; i++)
			{
				letters[word[i]-base]++;
			}
			int done = 0;
			int order[] = new int[26];
			for (int i=0; i<26; i++)
			{
				order[i] = (source.lists[i].getLength(wlen) << 8) | i;
			}
			Arrays.sort(order);
			for (int x=0; x<26; x++)
			{
				int i = order[x] & 0xff;
				if (letters[i] > 0)
				{
					if (done++ == 0)
					{
						source.lists[i].addMatch(this, wlen, letters[i]);
					}
					else
					{
						delWords(wlen, i, letters[i]);
					}
				}
			}
			long tm = checkTimer();
			if (tm > 100)
			{
				debug("search("+new String(word)+") in "+tm+"ms = "+found);
			}
			
			} catch (Exception ex) {
				debug("SolutionSet.setWord() error with '"+new String(word)+"' len="+word.length);
				ex.printStackTrace();
			}
		}

		public void delWords(int wlen, int list, int repeat)
		{
			for (int i=0; i<matchlen; i++)
			{
				if (match[i] >= 0 && !source.lists[list].contains(wlen, match[i], repeat))
				{
					found--;
					match[i] = -1;
				}
			}
		}

		public void addWord(int index)
		{
			found++;
			match[matchlen++] = index;
			if (matchlen >= match.length)
			{
				int nmatch[] = new int[match.length+1000];
				System.arraycopy(match,0,nmatch,0,match.length);
				match = nmatch;
			}
		}

		public boolean contains(byte cword[])
		{
			if (cword.length != word.length || found == 0)
			{
				return false;
			}
			letter:
			for (int i=0; i<matchlen; i++)
			{
				if (match[i] >= 0)
				{
					byte test[] = source.getWord(match[i]);
					for (int j=0; j<test.length; j++)
					{
						if (test[j] != cword[j])
						{
							continue letter;
						}
					}
					return true;
				}
			}
			return false;
		}

		public int size()
		{
			return found;
		}

		public String[] getWords()
		{
			ArrayList w = new ArrayList();
			for (int i=0; i<matchlen; i++)
			{
				if (match[i] >= 0)
				{
					w.add(source.getWordString(match[i]));
				}
			}
			String wz[] = new String[w.size()];
			w.toArray(wz);
			return wz;
		}

		public void printWords()
		{
			for (int i=0; i<matchlen; i++)
			{
				if (match[i] >= 0)
				{
					debug(source.getWordString(match[i]));
				}
			}
		}
	}



	// ----------------------------------------------------------------------
	// ---( INNER CLASS MetaServer )---
	// ----------------------------------------------------------------------
	private class MetaServer extends Thread
	{
		private LinkedList players;
		private LinkedList games;

		public MetaServer()
		{
			super("MetaServer");
			players = new LinkedList();
			games = new LinkedList();
			new Thread("GameRefresh") {
				public void run()
				{
					long lastSend = 0;
					while (true)
					{
						try { sleep(5000); } catch (Exception ex) { }
						long time = System.currentTimeMillis();
						if (games.size() > 0 && (time - lastSend > 10000))
						{
							lastSend = time;
							sendGames();
						}
						for (Iterator i = games.iterator(); i.hasNext(); ) 
						{
							Game g = (Game) i.next();
							g.kickIdle();
						}
						Player[] pl = getPlayers();
						for (int i=0; i<pl.length; i++) {
							Player p = pl[i];
							if (p.getFreshness() > 30000 && !p.isBot()) 
							{
								debug("Player "+p+" is stale and will be removed");
								try {
									p.linkDown(false);
								} catch (Exception ex) {
									ex.printStackTrace();
								}
							}
							else if (p.getFreshness() > 15000) 
							{
								p.sendPing();
							}
						}
					}
				}
			}.start();
		}

		private void startBots()
		{
			new Thread()
			{
				public void run()
				{
					try
					{
						Thread.sleep(500);
						new PlayerBot(BOT_LEVEL1, "Trainer Bot 1", BEGINNER_GAME_1);
						Thread.sleep(500);
						new PlayerBot(BOT_LEVEL1, "Trainer Bot 2", BEGINNER_GAME_2);
						Thread.sleep(500);
						new PlayerBot(BOT_LEVEL2, "Trainer Bot 3", INTERMEDIATE_GAME);
						Thread.sleep(500);
						new PlayerBot(BOT_LEVEL3, "Trainer Bot 4", ADVANCED_GAME);
					}
					catch (Exception ex)
					{
						ex.printStackTrace();
					}
				}
			}.start();
		}


		public void linkUp(Socket sock) throws IOException, InterruptedException {
			final Player pl = new Player(this, sock);
			try {
				debug("linkUp "+sock.getRemoteSocketAddress()+" "+pl);
			} catch (Exception ex) {
				debug("linkUp error "+pl);
			}
			addPlayer(pl);
			new Thread() {
				public void run() {
					setName("send hello to "+pl);
					try {
						sleep(1000);
						pl.sendHello();
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
			}.start();
		}

		public void run()
		{
			try
			{
				if (startServer) 
				{
					new Thread() {
						public void run() {
							try {
								ServerSocket ss = new ServerSocket(sport);
								debug("started listener on port "+ss.getLocalPort());
								while (true) {
									linkUp(ss.accept());
								}
							} catch (Exception ex) {
								ex.printStackTrace();
							}
						}
					}.start();
				}
				if (startBots) 
				{
					debug("starting bots");
					startBots();
				}
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
			}
		}

		public Game newGame(Player creator, String name, int level, int speed, String dict) throws Exception
		{
			Game g = new Game(this, creator, name, speed, level, dict);
			if (addGame(g))
			{
				return g;
			}
			else
			{
				return null;
			}
		}

		public boolean addGame(Game ng)
		{
			String gname = ng.getName();
			synchronized (this) {
				Game g[] = getGames();
				for (int i=0; i<g.length; i++)
				{
					if (g[i].getName().equals(gname))
					{
						return false;
					}
				}
				if (gname.equals(BEGINNER_GAME_1)) 
				{
					games.add(0,ng);
				} 
				else
				if (gname.equals(BEGINNER_GAME_2) && games.size() > 0) 
				{
					games.add(1,ng);
				} 
				else
				if (gname.equals(INTERMEDIATE_GAME) && games.size() > 1) 
				{
					games.add(2,ng);
				}
				else
				if (gname.equals(ADVANCED_GAME) && games.size() > 2)
				{
					games.add(3,ng);
				}
				else 
				{
					games.add(ng);
				}
			}
			sendGames();
			return true;
		}

		public void removeGame(Game g)
		{
			games.remove(g);
			sendGames();
		}

		public void addPlayer(Player p)
		{
			synchronized (players) {
				players.add(p);
			}
		}

		public void hello(Player p)
		{
			p.sendMessage("There are "+(players.size()-1)+" other players online");
			if (players.size() > 1)
			{
				StringBuffer sb = new StringBuffer();
				Player pl[] = getPlayers();
				for (int i=0; i<pl.length; i++)
				{
					if (p != pl[i] && pl[i].getName() != null && !pl[i].isPlaying() && !pl[i].isReserve())
					{
						if (sb.length() > 0)
						{
							sb.append(", ");
						}
						sb.append(pl[i].getName());
					}
				}
				if (sb.length() > 0)
				{
					p.sendMessage("In the lobby: "+sb.toString());
				}
				else
				{
					p.sendMessage("No players are in the lobby");
				}
			}
			try {
				File motd = new File("motd");
//				if (motd.exists()) {
//					p.sendMessage(Codec.toString(Files.read(motd)));
//				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}

		public void removePlayer(Player p)
		{
			synchronized (players) {
				players.remove(p);
			}
			if (p.getName() != null && p.isNameSet())
			{
				sendAll(Player.LEAVE, p.getName());
				broadcast("'"+p+"' leaves the lobby");
			}
		}

		public synchronized Game[] getGames()
		{
			Game g[] = new Game[games.size()];
			games.toArray(g);
			return g;
		}

		public synchronized Player[] getPlayers()
		{
			Player p[] = new Player[players.size()];
			players.toArray(p);
			return p;
		}

		public void joinGame(Player pl, String name)
		{
			Game g[] = getGames();
			for (int i=0; i<g.length; i++)
			{
				if (g[i].getName().equals(name))
				{
					broadcast("'"+pl+"' joined the game '"+name+"'");
					g[i].addPlayer(pl);
					return;
				}
			}
		}

		public void leaveGame(Player pl, String name)
		{
			broadcast("'"+pl+"' entered the lobby from the game '"+name+"'");
			hello(pl);
		}

		public void sendGames()
		{
			Game g[] = server.getGames();
			String args[] = new String[g.length];
			for (int i=0; i<args.length; i++)
			{
				args[i] = g[i].getInfo();
				if (g[i].hasTimeout())
				{
					long sinceLastSend = g[i].sinceLastSend();
					if (sinceLastSend > GameExpireTime)
					{
						g[i].warning("Game is terminated");
						g[i].cancelGame();
					}
					else
					if (sinceLastSend > GameExpireWarning)
					{
						g[i].warning("Game will terminate with no further activity");
					}
				}
			}
			sendAll(Player.GAMES, args);
		}

		public boolean setName(Player pl, String name)
		{
			String lname = name.toLowerCase();
			Player plist[] = getPlayers();
			for (int i=0; i<plist.length; i++) {
				Player p = plist[i];
				if (p != pl && p.getName() != null && p.getName().toLowerCase().equals(lname))
				{
					return false;
				}
			}
			if (pl.getName() == null)
			{
				sendAll(Player.JOIN, name);
				broadcast("Player '"+name+"' enters the lobby");
			}
			else if (!name.equals(pl.getName()))
			{
				broadcast("Player '"+pl.getName()+"' is now known as '"+name+"'");
			}
			pl.setName(name);
			return true;
		}

		public boolean reserve(Player pl, String name) {
			String lname = name.toLowerCase();
			Player plist[] = getPlayers();
			for (int i=0; i<plist.length; i++) {
				Player p = plist[i];
				if (p != pl && p.getName() != null && p.getName().toLowerCase().equals(lname))
				{
					return false;
				}
			}
			pl.reserveName(name);
			return true;
		}
		
		public boolean restore(Player pl, String res) {
			long resid = Long.parseLong(res,16);
			Player plist[] = getPlayers();
			for (int i=0; i<plist.length; i++) {
				Player p = plist[i];
				if (p != pl && p.getReserve() == resid)
				{
					pl.setName(p.getName());
					return true;
				}
			}
			return false;
		}
		
		public void broadcast(String msg)
		{
			sendAll(Player.MESSAGE, new String[] { msg });
		}

		public void sendChat(String user, String msg)
		{
			sendAll(Player.CHAT, new String[] { user, msg });
		}

		public void sendAll(String cmd, String arg)
		{
			sendAll(cmd, new String[] { arg });
		}

		public void sendAll(String cmd, String args[])
		{
			if (cmd != Player.GAMES)
			{
				debug(cmd+" : "+Player.toString(args));
			}
			Player plist[] = getPlayers();
			for (int i=0; i<plist.length; i++) {
				Player p = plist[i];
				if (!p.isPlaying())
				{
					p.sendCommand(cmd, args);
				}
			}
		}
	}



	// ----------------------------------------------------------------------
	// ---( INNER CLASS Game )---
	// ----------------------------------------------------------------------
	private class Game
	{
		private MetaServer server;
		private WordSource source;
		private Player creator;
		private String guid;
		private String dictName;
		private String gameName;
		private int gameSpeed;
		private int gameLevel;
		private ArrayList words;
		private ArrayList players;
		private Pool pool;
		private Thread dealer;
		private int state;
		private long dealInterval;
		private long graceInterval;
		private long endGameInterval;
		private long lastSend;
		private long lastAction;
		private long lastTake;
		private long startTime;
		private boolean endgame;
		private boolean onTheClock;
		private boolean timeout;
		private int spectators;

		public Game(MetaServer server, Player creator, String name, int speed, int level, String dictName) throws Exception
		{
			this.source = (WordSource) dict.get(dictName.toLowerCase());
			this.server = server;
			this.dictName = dictName;
			this.creator = creator;
			gameName = name;
			gameSpeed = speed;
			gameLevel = level;
			players = new ArrayList();
			timeout = true;
			setup();
		}

		public ArrayList players() {
			return new ArrayList(players);
		}
		
		private void setup()
		{
			words = new ArrayList();
			pool = new Pool();
			state = WAITING;
			endGameInterval = endGamePeriod[gameSpeed];
			graceInterval = gracePeriod[gameSpeed];
			dealInterval = dealSpeed[gameSpeed];
			endgame = false;
			lastSend = time();
			this.guid = genUID();
		}

		private void debug(String msg)
		{
			Scramble.this.debug("<"+gameName+"> "+msg);
		}

		public String getName()
		{
			return gameName;
		}

		private String zpad(long l)
		{
			if (l >= 10)
			{
				return Long.toString(l);
			}
			else
			{
				return "0"+l;
			}
		}

		public String getInfo()
		{
			long time = (time()-startTime)/1000;
			long mins = time / 60;
			long secs = time % 60;
			int pl = players.size();
			int sp = 0;
			for (int i=0; i<players.size(); i++)
			{
				if (((Player)players.get(i)).isSpectator())
				{
					pl--;
					sp++;
				}
			}
			String inplay = (state == INPLAY ? (zpad(mins)+":"+zpad(secs)) : "WAITING");
			return gameName+"\1"+pl+" / "+sp+"\1"+gameLevel+"\1"+gameSpeed+"\1"+"\1"+inplay+"\1"+dictName;
		}

		public void kickIdle()
		{
			if (state == WAITING)
			{
				List pcopy = players();
				for (Iterator i = pcopy.iterator(); i.hasNext(); ) {
					Player p = (Player)i.next();
					if (p.isReady()) {
						for (Iterator j = pcopy.iterator(); j.hasNext(); ) {
							Player p2 = (Player)j.next();
							if (!p2.isBot() && !p2.isReady() && p2.getFreshness() > 10000)
							{
								p2.CMD_leave(null);
								broadcast(p2+" was booted for sitting around while others want to play");
							}
						}
						return;
					}
				}
			}
		}

		public void checkReady(Player player)
		{
			List plist = players();
			if (state != INPLAY) 
			{
				broadcast(player+" is "+(player.isReady() ? "" : "not ")+"ready to play");
				if (state == WAITING)
				{
					for (Iterator i = players().iterator(); i.hasNext(); ) {
						Player p = (Player)i.next();
						p.setFresh();
					}
					for (Iterator i = players().iterator(); i.hasNext(); ) {
						Player p = (Player)i.next();
						if (!p.isReady())
						{
							return;
						}
					}
					state = READY;
					broadcast("All players are ready to play");
					startDealing();
				}
			}
			else
			{
				if (players.contains(player)) {
					if (player.isReady())
					{
						broadcast(player+" will continue to play");
					}
					else
					{
						broadcast(player+" wishes to quit");
					}
				}
				for (Iterator i = players().iterator(); i.hasNext(); ) {
					Player p = (Player)i.next();
					if (p.isReady() && !p.isBot())
					{
						return;
					}
				}
				broadcast("All players agree to quit");
				gameOver();
			}
		}

		public void sendTiles()
		{
			sendAll(Player.POOL, pool.getTilesString());
		}

		public void sendTiles(Player pl)
		{
			pl.sendCommand(Player.POOL, pool.getTilesString());
		}

		public long time()
		{
			return System.currentTimeMillis();
		}

		public long sinceLastAction()
		{
			return time() - (Math.max(lastTake,lastAction));
		}

		public long sinceLastTake()
		{
			return time() - lastTake;
		}

		public long sinceLastSend()
		{
			return time() - lastSend;
		}

		public void setTimeout(boolean tmout)
		{
			timeout = tmout;
		}

		public boolean hasTimeout()
		{
			return timeout;
		}

		public void gameOver()
		{
			if (state == WAITING)
			{
				return;
			}
			Player tie = null;
			Player win = null;
			for (Iterator i = players().iterator(); i.hasNext(); ) {
				Player p = (Player)i.next();
				if (win == null || p.numTiles() > win.numTiles())
				{
					win = p;
					tie = null;
					continue;
				}
				if (win != null && p.numTiles() == win.numTiles())
				{
					tie = p;
				}
			}
			if (players.size() > 0)
			{
				if (tie != null)
				{
					//broadcast("** And it's a tie between "+tie+"! **");
					sendAll(Player.GAMEOVER, "** And it's a tie between "+tie+" and "+win+"! **");
				}
				else
				{
					//broadcast("*** And the winner is "+win+"! ***");
					sendAll(Player.GAMEOVER, "*** And the winner is "+win+"! ***");
				}
			}
			setup();
		}

		private long getDealSleep()
		{
			return dealInterval + (long)(pool.numShowing() * 50); 
		}

		private void startDealing()
		{
			if (state != READY)
			{
				broadcast("all players are not ready");
				return;
			}
			state = INPLAY;
			dealer = new Thread("Dealer") {
				public void run()
				{
					try
					{
					
					sendAll(Player.NEWGAME, gameName);
					broadcast("The game will start in 3 seconds");
					sendAll(Player.TIMER, "3");
					sleep(3000);
					if (state == INPLAY)
					{
						startTime = time();
						broadcast("The game is now in play");
						onTheClock = false;
						pool.deal();
						pool.deal();
					}
					while (state == INPLAY)
					{
						if (pool.deal())
						{
							sendTiles();
							updateAction();
							sleep(getDealSleep());
						}
						else
						{
							if (!endgame && !pool.hasMoreTiles())
							{
								broadcast("No more tiles. Endgame begins now.");
								endgame = true;
							}
							if (sinceLastAction() > endGameInterval)
							{
								gameOver();
								return;
							}
							if (!onTheClock)
							{
								updateAction();
								onTheClock = true;
								String i = Long.toString(endGameInterval/1000);
								broadcast("Game over in "+i+" seconds");
								sendAll(Player.TIMER, i);
							}
							waitForTake();
						}
						while (sinceLastTake() < graceInterval)
						{
							sleep(graceInterval - sinceLastTake());
						}
					}

					}
					catch (Exception ex)
					{
						ex.printStackTrace();
					}
				}
			};
			dealer.start();
		}

		public int getState()
		{
			return state;
		}

		public void addPlayer(Player player)
		{
			boolean spec = (players.size() - spectators) >= 4;
			if (spec)
			{
				spectators++;
			}
			player.joinGame(this, spec);
			debug("player '"+player+"' joined game '"+gameName+"'");
			players.add(player);
			player.sendCommand(Player.NEWGAME, gameName);
			if (player.isSpectator())
			{
				player.sendMessage("This game is full. You are a spectator.");
				broadcast(player+" joins as a spectator");
			}
			else
			{
				sendAll(Player.JOIN, player.getName());
				if (state == INPLAY && player.uuid != null) {
					player.setReady(true);
				}
			}
			for (Iterator i = players().iterator(); i.hasNext(); ) {
				Player p = (Player)i.next();
				if (!(p == player || p.isSpectator()))
				{
					player.sendCommand(Player.PLAYER, p.getStatus());
				}
			}
			sendTiles(player);
			server.sendGames();
		}

		public void cancelGame()
		{
			for (Iterator i = new LinkedList(players).iterator(); i.hasNext(); ) {
				Player p = (Player)i.next();
				p.sendCommand(Player.LEAVE, p.getName());
				p.CMD_leave(null);
			}
			server.removeGame(this);
			gameOver();
			server.sendGames();
		}

		public void removePlayer(Player player)
		{
			try {
debug(this+" removePlayer "+player+" spectator("+player.isSpectator()+") from "+players);
				try {
					if (!players.remove(player))
					{
						debug("failed to remove '"+player+"' from '"+this+"'");
					}
				} catch (Exception ex) {
					System.out.println("err removing "+player+" from "+this);
					ex.printStackTrace();
				}
				try {
					if (player.isSpectator())
					{
						spectators--;
//						player.gameStat.finish = 4;
					}
				} catch (Exception ex) {
					ex.printStackTrace();
					spectators--;
				}
				try {
					player.sendCommand(Player.LEAVE, player.getName());
					while (loseShortest(player, "player left game", true))
						;
				} catch (Exception ex) {
					System.out.println("err sending leave for "+player+" from "+this);
					ex.printStackTrace();
				}
				sendAll(Player.LEAVE, player.getName());
				if (players.size() == 0)
				{
					debug("the last player left");
					server.removeGame(this);
					gameOver();
				} else {
					checkReady(player);
				}
				server.sendGames();
			} catch (Exception ex) {
				debug("removePlayer catch all for errors");
				ex.printStackTrace();
			}
		}

		public void warning(String msg)
		{
			long last = lastSend;
			sendAll(Player.CHAT, msg);
			lastSend = last;
		}

		public void broadcast(String msg)
		{
			sendAll(Player.MESSAGE, msg);
		}

		public void sendChat(String user, String msg)
		{
			String lower = msg.toLowerCase();
			if (lower.startsWith("kick ") && msg.length() > 5)
			{
				String target = msg.substring(5);
				debug (user+" attempting to kick '"+target+"'");
				for (Iterator i = players().iterator(); i.hasNext(); ) {
					Player p = (Player)i.next();
					if (!p.isReady() && p.getName().equals(target))
					{
						p.CMD_leave(null);
					}
				}
			}
			sendAll(Player.CHAT, new String[] { user, msg });
		}

		public void sendAll(String cmd, String arg)
		{
			sendAll(cmd, new String[] { arg });
		}

		public void sendAll(String cmd)
		{
			sendAll(cmd, "");
		}

		public synchronized void sendAll(String cmd, String args[])
		{
			lastSend = time();
			if (cmd != Player.POOL && cmd != Player.TIMER)
			{
				debug(cmd+" : "+Player.toString(args));
			}
			for (Iterator i = players().iterator(); i.hasNext(); ) {
				Player p = (Player)i.next();
				p.sendCommand(cmd, args);
			}
		}

		public void waitForTake()
		{
			synchronized (pool)
			{
				if (sinceLastTake() < graceInterval)
				{
					return;
				}
				try { pool.wait(getDealSleep()); } catch (Exception ex) { }
			}
		}

		public void updateAction()
		{
			lastAction = time();
			if (onTheClock)
			{
				onTheClock = false;
				sendAll(Player.TIMER, "-1");
			}
		}

		public void updateTake()
		{
			synchronized (pool)
			{
				lastTake = time();
				pool.notify();
			}
			updateAction();
		}

		private void take(Player player, Word word)
		{
			updateTake();
			player.addWord(word);
			words.add(word);
			word.setPlayer(player);
			sendAll(Player.TAKE, new String[] { player.getName(), word.toString() });
		}

		private void steal(Player player, Word oldword, Word newword)
		{
			updateTake();
			Player owner = oldword.getPlayer();
			debug("'"+player+"' steals '"+oldword+"' with '"+newword+"'"+" from '"+owner+"'");
			if (owner != null)
			{
				sendAll(Player.LOSE, new String[] {owner.getName(), oldword.toString(), player.getName()});
				owner.delWord(oldword);
			}
			else
			{
				debug(oldword+" has no owner!");
			}
			player.addWord(oldword);
			oldword.update(newword);
			sendAll(Player.TAKE,
				new String[] { player.getName(), oldword.toString() });
		}

//		private void clearWordMarks(Vector v)
//		{
//			for (Enumeration e = v.elements(); e.hasMoreElements(); )
//			{
//				Word w = (Word)e.nextElement();
//				w.clearMarks();
//			}
//		}

		private boolean loseShortest(Player pl, String because)
		{
			return loseShortest(pl, because, false);
		}

		private boolean loseShortest(Player pl, String because, boolean force)
		{
			if (!force && sinceLastTake() < graceInterval)
			{
				pl.sendMessage(because+", but it's too close to the last play so I'll forget it");
				return false;
			}
			Word word = pl.getShortest();
			if (word != null)
			{
				sendAll(Player.LOSE, new String[] { pl.toString(), word.toString() });
				broadcast(pl+" loses '"+word+"' because "+because);
				pl.delWord(word);
				words.remove(word);
				pool.reAdd(word);
				return true;
			}
			else
			{
				return false;
			}
		}

		public synchronized boolean takeWord(Player player, byte word[])
		{
			boolean saved = false;
			int wlen = word.length;
			Word find = new Word(word);
			startTimer();
			if (wlen < 3)
			{
				player.sendMessage("Words must be at least three letters");
				return false;
			}
			// is it a word?
			SolutionSet ss = source.getWords(word);
			if (!ss.contains(word))
			{
				String msg = "'"+find+"' is not a word";
				if (endgame)
				{
					player.sendMessage(msg+". no loss in endgame.");
					return false;
				}
				loseShortest(player, msg);
				return false;
			}
			// is it in the pool?
			int poolMatches = pool.markMatches(find);
			if (poolMatches == wlen)
			{
				if (player.maxWords())
				{
					saved = true;
					player.sendMessage("you've reached your word limit");
				}
				else
				{
					broadcast(player+" took '"+new String(word)+"'");
					pool.takeMarked();
					sendTiles();
					take(player, find);
					return true;
				}
			}
			// look for most letter matches in Player Words
			ArrayList match = new ArrayList();
			for (Iterator i = words.iterator(); i.hasNext(); ) {
				Word w = (Word)i.next();
				if (w.length() > wlen)
				{
					continue;
				}
				int mlen = w.markMatches(find, true);
				w.clearMarks();
				if (mlen < w.length())
				{
					continue;
				}
				match.add(w);
			}
			// if any words match, walk the list longest to shortest
			if (match.size() > 0)
			{
				Word words[] = new Word[match.size()];
				match.toArray(words);
				Arrays.sort(words, comp);
				//debug("words w/ matches : "+match);
				for (int i=0; i<words.length; i++)
				{
					Word w = words[i];
					int m = find.markMatches(w, true);
					int p = pool.markMatches(find);
					//debug("testing '"+w+"' match="+m+" pool="+p);
					find.clearMarks();
					// check if we can steal (no used permutations)
					if (m == wlen)
					{
						if (source.noPlurals() && w.isPluralPermutation(find))
						{
							player.sendMessage("you cannot steal/defend by making a plural");
							saved = true;
							continue;
						}
						if (w.isWordUsed(find))
						{
							Player op = w.getPlayer();
							if (op == player)
							{
								broadcast(player+" defended '"+w+"' with '"+find+"'");
							}
							else
							{
								if (player.maxWords())
								{
									saved = true;
									player.sendMessage("you've reached your word limit");
									continue;
								}
								broadcast(player+" stole '"+w+"' from "+op+" with '"+find+"'");
							}
							steal(player, w, find);
							return true;
						}
						else
						{
							saved = true;
							if (w.equals(find))
							{
								player.sendMessage("'"+find+"' already taken");
								continue;
							}
							player.sendMessage("can't steal '"+w+"' with '"+find+"' "+"because it's been used");
							continue;
						}
					}
					else
					if (source.noPlurals() && m == wlen - 1 && (w.toString()+'s').equals(find.toString()))
					{
						player.sendMessage("you cannot steal/defend by making a plural");
						saved = true;
						continue;
					}
					if (m+p == wlen)
					{
						pool.takeMarked();
						sendTiles();
						Player op = w.getPlayer();
						if (op == player)
						{
							broadcast(player+" defended '"+w+"' with '"+find+"'");
						}
						else
						{
							if (player.maxWords())
							{
								saved = true;
								player.sendMessage("you've reached your word limit");
								continue;
							}
							broadcast(player+" stole '"+w+"' from "+op+" with '"+find+"'");
						}
						steal(player, w, find);
						return true;
					}
				}
			}
			// saved is set if in grace period or used match
			// or attempted plural
			if (!saved)
			{
				String msg = "'"+find+"' is not on the board";
				if (endgame)
				{
					player.sendMessage(msg+". no loss in endgame.");
					return false;
				}
				loseShortest(player, msg);
			}
			return false;
			//debug("total search took "+checkTimer()+"ms");
		}

		private void showState()
		{
			pool.print();
			for (Iterator i = players().iterator(); i.hasNext(); ) {
				Player p = (Player)i.next();
				p.printWords();
			}
		}
	}



	// ----------------------------------------------------------------------
	// ---( INNER CLASS WordComp )---
	// ----------------------------------------------------------------------
	private class WordComp implements Comparator
	{
		public int compare(Object w1, Object w2) {
			Word o1 = (Word)w1;
			Word o2 = (Word)w2;
			int l1 = o1.length();
			int l2 = o2.length();
			if (l1 == l2)
			{
				return 0;
			}
			if (l1 > l2)
			{
				return 1;
			}
			else
			{
				return -1;
			}
		}
	}



	// ----------------------------------------------------------------------
	// ---( INNER CLASS Word )---
	// ----------------------------------------------------------------------
	private class Pool
	{
		private int tiles[] = {
			'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
			'b', 'b',
			'c', 'c',
			'd', 'd', 'd', 'd',
			'e', 'e', 'e', 'e', 'e', 'e', 'e', 'e', 'e', 'e', 'e', 'e', 
			'f', 'f',
			'g', 'g', 'g',
			'h', 'h',
			'i', 'i', 'i', 'i', 'i', 'i', 'i', 'i', 'i',
			'j',
			'k',
			'l', 'l', 'l', 'l',
			'm', 'm',
			'n', 'n', 'n', 'n', 'n', 'n',
			'o', 'o', 'o', 'o', 'o', 'o', 'o', 'o',
			'p', 'p',
			'q',
			'r', 'r', 'r', 'r', 'r', 'r',
			's', 's', 's', 's',
			't', 't', 't', 't', 't', 't',
			'u', 'u', 'u', 'u',
			'v', 'v',
			'w', 'w',
			'x',
			'y', 'y',
			'z'
		};
		private int numtiles = tiles.length;
		private int showing;
		private boolean moreTiles;

		private int HIDE = 0;
		private int SHOW = 1 << 8;
		private int TAKE = 1 << 9;
		private int MARK = 1 << 10;

		private int XSHOW = SHOW ^ 0xffff;
		private int XTAKE = TAKE ^ 0xffff;
		private int XMARK = MARK ^ 0xffff;

		public Pool()
		{
			scramble();
			moreTiles = true;
		}

		private void scramble()
		{
			startTimer();
			Random r = new Random();
			int count = (int)(Math.abs(r.nextDouble())*1500)+1500;
			for (int i=0; i<count; i++)
			{
				int n1 = (int)(Math.abs(r.nextDouble())*tiles.length);
				int n2 = (int)((r.nextDouble())*tiles.length);
				if (n1 != n2)
				{
					int tmp = tiles[n1];
					tiles[n1] = tiles[n2];
					tiles[n2] = tmp;
				}
			}
			for (int i=0; i<tiles.length; i++)
			{
				if (!isShowing(i))
				{
					for (int j=i+1; j<tiles.length; j++)
					{
						if (isShowing(j))
						{
							int tmp = tiles[i];
							tiles[i] = tiles[j];
							tiles[j] = tmp;
						}
					}
				}
			}
			//debug("scrambled pool x"+count+" in "+checkTimer()+"ms");
		}

		public int numShowing()
		{
			return showing;
		}

		private void show(int tile)
		{
			tiles[tile] = tiles[tile] | SHOW;
			showing++;
		}

		private void unshow(int tile)
		{
			tiles[tile] = tiles[tile] & XSHOW;
			showing--;
		}

		private boolean isShowing(int tile)
		{
			return (tiles[tile] & SHOW) == SHOW;
		}

		private void take(int tile)
		{
			tiles[tile] = tiles[tile] | TAKE;
			if (isShowing(tile))
			{
				unshow(tile);
			}
		}

		private void untake(int tile)
		{
			tiles[tile] = tiles[tile] & XTAKE;
		}

		private boolean isTaken(int tile)
		{
			return (tiles[tile] & TAKE) == TAKE;
		}

		private void mark(int tile)
		{
			tiles[tile] = tiles[tile] | MARK;
		}

		private void unmark(int tile)
		{
			tiles[tile] = tiles[tile] & XMARK;
		}

		private boolean isMarked(int tile)
		{
			return (tiles[tile] & MARK) == MARK;
		}

		private byte getTile(int tile)
		{
			return (byte)(tiles[tile] & 0xff);
		}

		public boolean hasMoreTiles()
		{
			return moreTiles;
		}

		public boolean deal()
		{
			int count = 0;
			for (int i=0; i<tiles.length; i++)
			{
				if (isShowing(i) && !isTaken(i) && ++count >= 52)
				{
					return false;
				}
				if (!(isShowing(i) || isTaken(i)))
				{
					show(i);
					return true;
				}
			}
			moreTiles = false;
			return false;
		}

		public void reAdd(Word word)
		{
			byte letters[] = word.word;
			for (int i=0; i<letters.length; i++)
			{
				reAdd(letters[i]);
				moreTiles = true;
			}
			scramble();
		}

		public void reAdd(byte letter)
		{
			for (int i=0; i<numtiles; i++)
			{
				if (isTaken(i) && (getTile(i) == letter))
				{
					untake(i);
					return;
				}
			}
		}

		private byte[] getPool()
		{
			int pos = 0;
			byte b[] = new byte[showing];
			for (int i=0; i<numtiles; i++)
			{
				if (isShowing(i))
				{
					b[pos++] = getTile(i);
				}
			}
			return b;
		}

		public String getTilesString()
		{
			return new String(getPool());
		}

		private void clearMarks()
		{
			for (int i=0; i<numtiles; i++)
			{
				unmark(i);
			}
		}

		private void takeMarked()
		{
			boolean rescramble = false;
			for (int i=0; i<numtiles; i++)
			{
				if (isMarked(i))
				{
					take(i);
					unmark(i);
					rescramble = true;
				}
			}
			if (rescramble)
			{
				scramble();
			}
		}

		private int markMatches(Word find)
		{
			clearMarks();
			byte word[] = find.word;
			int marked = 0;
			for (int i=0; i<word.length; i++)
			{
				if (find.isMarked(word[i]))
				{
					continue;
				}
				for (int j=0; j<numtiles; j++)
				{
					if (isShowing(j) && !isMarked(j) && getTile(j) == word[i])
					{
						mark(j);
						marked++;
						break;
					}
				}
			}
			return marked;
		}

		private void print()
		{
			System.out.println("---- pool("+showing+") ----");
			int count = 0;
			int line = 0;
			for (int i=0; i<tiles.length; i++)
			{
				if (isShowing(i) && !isTaken(i))
				{
					count++;
					if (count > 4 && count % 4 == 1)
					{
						System.out.println();
						if ((++line % 2 == 1))
						{
							System.out.print("  ");
						}
					}
					System.out.print((char)(tiles[i]&0xdf)+"   ");
				}
			}
			System.out.println();
		}
	}


	// ----------------------------------------------------------------------
	// ---( INNER CLASS Word )---
	// ----------------------------------------------------------------------
	private class Word
	{
		private final byte MARK = (byte)0x80;
		private final byte XMARK = (byte)(MARK ^ 0xff);

		private byte word[];
		private Word next;
		private int marked;
		private int hashcode;
		private Player player;
		private LinkedList used;

		public Word(String word)
		{
			this(word.getBytes());
		}

		public Word(byte word[])
		{
			setBytes(word);
			this.used = new LinkedList();
		}

		public void setPlayer(Player player)
		{
			this.player = player;
		}

		public Player getPlayer()
		{
			return player;
		}

		/*
		 * new permutation or derived word
		 */
		public void update(Word newword)
		{
			used.add(new Word(word));
			setBytes(newword.word);
		}
		
		private void setBytes(byte b[])
		{
			word = b;
			hashcode = new String(b).hashCode();
		}

		public boolean isWordUsed(Word steal)
		{
			if (equals(steal))
			{
				return false;
			}
			for (Iterator i = used.iterator(); i.hasNext(); ) {
				Word w = (Word)i.next();
				if (w.equals(steal))
				{
					return false;
				}
			}
			return true;
		}

		public boolean isPluralPermutation(Word steal)
		{
			String ss = steal.toString();
			if (!ss.endsWith("s"))
			{
				return false;
			}
			int slen = steal.length() - 1;
			String match = ss.substring(0,slen);
			for (Iterator i = used.iterator(); i.hasNext(); ) {
				Word w = (Word)i.next();
				if (w.length() == slen && w.toString().equals(match))
				{
					return true;
				}
			}
			return false;
		}

		public int hashCode()
		{
			return hashcode;
		}

		public boolean equals(Object o)
		{
			if (o instanceof Word)
			{
				return equalsWord((Word)o);
			}
			else
			{
				return false;
			}
		}

		private boolean equalsWord(Word check)
		{
			byte cmp[] = check.word;
			if (word.length != cmp.length)
			{
				return false;
			}
			for (int i=0; i<cmp.length; i++)
			{
				if (clearMark(word[i]) != clearMark(cmp[i]))
				{
					return false;
				}
			}
			return true;
		}

		// --( letter flag methods )--
		private byte mark(int letter)
		{
			return (byte)(letter | MARK);
		}

		private boolean isMarked(int letter)
		{
			return (letter & MARK) == MARK;
		}

		private boolean isAvailable(byte letter)
		{
			return (letter > 0 && !isMarked(letter));
		}

		private byte clearMark(byte letter)
		{
			return (byte)(letter & XMARK);
		}

		public int numMarked()
		{
			return marked;
		}

		public void clearMarks()
		{
			if (marked > 0)
			{
				for (int i=0; i<word.length; i++)
				{
					word[i] = clearMark(word[i]);
				}
				marked = 0;
			}
		}

		public int markMatches(Word check, boolean clear)
		{
			if (clear)
			{
				clearMarks();
			}
			int match = 0;
			byte cword[] = check.word;
			for (int i=0; i<cword.length; i++)
			{
				if (isMarked(cword[i]))
				{
					continue;
				}
				for (int j=0; j<word.length; j++)
				{
					if (isMarked(word[j]))
					{
						continue;
					}
					if (cword[i] == word[j])
					{
						word[j] = mark(word[j]);
						match++;
						break;
					}
				}
			}
			marked += match;
			return match;
		}

		public Word getNext()
		{
			return next;
		}

		public void setNext(Word word)
		{
			next = word;
		}

		public int length()
		{
			return word.length;
		}

		public String toString()
		{
			return new String(word);
		}
	}


	// ----------------------------------------------------------------------
	// ---( INNER CLASS Player )---
	// ----------------------------------------------------------------------
	private class Player extends ScrambleLink
	{
		private MetaServer server;
		private Game game;
		private String name;
		private LinkedList words;
		private boolean setName;
		private boolean ready;
		private boolean spectator;
		private long reserve;
		private String uuid;
		private String site;

		public Player(MetaServer server)
		{
			this.server = server;
		}

		public Player(MetaServer server, Socket sock)
			throws IOException, InterruptedException
		{
			this(server);
			setup(sock);
			debug("connect ("+hostname()+")");
		}

		public void joinGame(Game game, boolean spectator)
		{
			this.game = game;
			this.words = new LinkedList();
			this.ready = false;
			this.spectator = spectator;
		}

		public String getName()
		{
			return name;
		}
		
		public void reserveName(String name) {
			this.name = name;
		}

		public void setName(String name) {
			this.name = name;
			this.setName = true;
		}

		public boolean isNameSet() {
			return setName;
		}
		
		public Game getGame()
		{
			return game;
		}

		public String toString()
		{
			return name;
		}

		public boolean isBot() {
			return name != null && name.indexOf("Trainer Bot") == 0;
		}

		public boolean isSpectator()
		{
			return spectator;
		}

		public boolean isPlaying()
		{
			return game != null;
		}
		
		public boolean isReserve() {
			return reserve != 0;
		}
		
		public long getReserve() {
			return reserve;
		}

		public void reset()
		{
			words = new LinkedList();
		}

		public void setReady(boolean ready)
		{
			this.ready = ready;
		}

		public int numTiles()
		{
			int num = 0;
			for (Iterator i = words.iterator(); i.hasNext(); ) {
				Word w = (Word)i.next();
				num += w.length();
			}
			return num;
		}

		public void addWord(Word word)
		{
			words.add(word);
			word.setPlayer(this);
		}

		public void delWord(Word word)
		{
			// this fails b/c words are not equivalent! b/c history
			if (!words.remove(word))
			{
				debug("*** error: player '"+name+"' doesn't have '"+word+"' ***");
			}
		}

		public Word getShortest()
		{
			Word shortest = null;
			for (Iterator i = words.iterator(); i.hasNext(); ) {
				Word next = (Word)i.next();
				if (shortest == null || next.length() < shortest.length())
				{
					shortest = next;
				}
			}
			return shortest;
		}

		public boolean maxWords()
		{
			return words.size() >= 24;
		}

		public boolean isReady()
		{
			return ready || spectator;
		}

		public void printWords()
		{
			debug("--( "+name+" )--");
			for (Iterator i = words.iterator(); i.hasNext(); ) {
				Word w = (Word)i.next();
				debug(w.toString());
			}
		}

		private String hostname()
		{
			try
			{
				return getSocket().getInetAddress().getHostAddress();//.getHostName();
			}
			catch (Throwable t)
			{
				return "local";
			}
		}

		public void linkDown(boolean onError)
		{
			if (name == null)
			{
				debug("disconnect ("+hostname()+")");
			}
			else
			{
				debug("disconnect '"+this+"'");
			}
			if (game != null)
			{
				game.removePlayer(this);
			}
			server.removePlayer(this);
			super.linkDown(onError);
		}

		public String[] getStatus()
		{
			String w[] = new String[words.size()+1];
			int pos = 0;
			w[pos++] = getName();
			for (Iterator i = words.iterator(); i.hasNext(); ) {
				Word next = (Word)i.next();
				w[pos++] = next.toString();
			}
			return w;
		}

		// ---( CMD methods )---
		
		public void CMD_hello(String args[])
		{
			server.hello(this);
			if (args.length > 0) {
				this.site = args[0];
			}
		}

		public void CMD_ping(String args[])
		{
		}
		
		public void CMD_restore(String args[])
		{
			if (server.restore(this, args[0])) {
				sendName(getName());
			} else {
				sendDeny(args[0]);
			}
		}
		
		public void CMD_reserve(String args[])
		{
			if (server.reserve(this, args[0])) {
				this.name = args[0];
				this.reserve = Math.abs(new Random().nextLong());
				sendReserve(Long.toString(reserve,16));
			} else {
				sendDeny(args[0]);
			}
		}

		public void CMD_chat(String args[])
		{
			if (name == null)
			{
				sendMessage("you must pick a name before chatting");
				return;
			}
			if (game != null)
			{
				game.sendChat(name, toString(args));
			}
			else
			{
				server.sendChat(name, toString(args));
			}
		}

		public void CMD_chatto(String args[])
		{
			if (args.length < 2) {
				sendMessage("chatto: missing arguments");
				return;
			}			
			if (name == null)
			{
				sendMessage("you must pick a name before chatting");
				return;
			}
			if (game != null)
			{
				for (Iterator i = game.players.iterator(); i.hasNext(); ) {
					Player p = (Player)i.next();
					if (p.getName().equals(args[0])) {
						p.sendChat(name+","+args[1]);
						return;
					}
				}
			}
			else
			{
				for (Iterator i = server.players.iterator(); i.hasNext(); ) {
					Player p = (Player)i.next();
					if (p.getName().equals(args[0])) {
						p.sendChat(name+","+args[1]);
						return;
					}
				}
			}
			sendMessage("no player named '"+args[0]+"' could be found");
		}

		public void CMD_name(String args[])
		{
			if (args.length < 1)
			{
				return;
			}
			if (server.setName(this, args[0]))
			{
				if (name == null)
				{
					debug("connect ("+hostname()+") is now '"+args[0]+"'");
				}
				sendName(name);
			}
			else
			{
				sendDeny(args[0]);
				sendMessage("the name '"+args[0]+"' is taken or reserved");
			}
		}
		
		public void CMD_join(String args[])
		{
			if (name == null)
			{
				sendMessage("you must pick a name before joining a game");
				return;
			}
			if (game == null)
			{
				server.joinGame(this, args[0]);
			}
		}

		public void CMD_kick(String args[])
		{
			debug("player '"+this+"' kicking '"+args[0]+"' from game '"+game.getName()+"'");
			if (game != null && game.state == WAITING) {
				for (Iterator i = new ArrayList(game.players).iterator(); i.hasNext(); ) {
					Player p = (Player)i.next();
					if (p.getName().equals(args[0]) && !p.ready && !p.isBot()) {
						sendMessage(getName()+" kicked '"+args[0]+"' out of the game");
						p.CMD_leave(args);
					}
				}
			}
		}

		public void CMD_leave(String args[])
		{
			if (game != null)
			{
				game.removePlayer(this);
				server.leaveGame(this, game.getName());
				game = null;
			}
		}

		public void CMD_newgame(String args[])
		{
			if (name == null)
			{
				sendMessage("you must pick a name before creating a game");
				return;
			}
			debug("new game : "+Link.toString(args));
			if (game != null)
			{
				return;
			}
			Game g;
			try {
				g = server.newGame(
					this,
					args[0],
					Integer.parseInt(args[1]),
					Integer.parseInt(args[2]),
					args.length > 3 ? args[3] : "Default"
				);
			} catch (Exception e) {
				e.printStackTrace();
				sendMessage("Internal error creating game");
				return;
			}
			if (g == null)
			{
				sendMessage("Game name must be unique");
			}
			else
			{
				server.joinGame(this, args[0]);
			}
		}

		public void CMD_games(String args[])
		{
			Game g[] = server.getGames();
			args = new String[g.length];
			for (int i=0; i<args.length; i++)
			{
				args[i] = g[i].getInfo();
			}
			sendCommand(GAMES, args);
		}

		public void CMD_ready(String args[])
		{
			if (args.length < 1)
			{
				return;
			}
			if (isSpectator())
			{
				sendMessage("you're just a spectator. you can't play!");
				return;
			}
			boolean newstate = args[0].equals("true");
			if (newstate != ready)
			{
				setReady(newstate);
				if (game != null)
				{
					game.sendAll(READY, new String[] { getName(), args[0] });
					game.checkReady(this);
				}
			}
		}
		
		public void CMD_quit(String args[]) 
		{
			linkDown(false);
		}
		
		public void CMD_uuid(String args[]) 
		{
			uuid = args[0];
			try {
				while (true) {
					if (uuid.equals("-")) {
						uuid = genUID();
						sendUUID(uuid);
					}
					break;
				}
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		
		public void CMD_take(String args[])
		{
			if (args.length < 1)
			{
				return;
			}
			if (isSpectator())
			{
				sendMessage("you're just a spectator. you can't play!");
				return;
			}
			if (game.getState() != INPLAY)
			{
				sendMessage("whoa there, speedy! the game is not yet in play.");
				return;
			}
			//game.debug("take : "+name+" : '"+toString(args)+"'");
			try
			{
				if (game.takeWord(this, args[0].toLowerCase().getBytes()))
				{
					// we used to send tiles at this point
					// game.sendTiles();
				}
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				sendMessage("'"+args[0]+"' is an illegal word");
			}
		}

		public void CMD_other(String args[])
		{
			if (args.length > 0)
			{
				if (args[0].equalsIgnoreCase("notimeout"))
				{
					game.setTimeout(false);
				}
				else
				if (args.length > 1 && args[0].equals("sysinfo"))
				{
					debug("sysinfo ("+toString(args,1)+")");
				}
				else
				{
					debug("other : "+toString(args));
				}
			}
		}

		public void CMD_hint(String args[])
		{
			if (args.length < 1)
			{
				return;
			}
			SolutionSet ss = game.source.getWords(args[0]);
			if (ss.size() > 0)
			{
				sendCommand("(-)", ss.getWords());
			}
			for (int i=0; i<26; i++)
			{
				char add = (char)('a'+i);
				ss = game.source.getWords(args[0]+add);
				if (ss.size() > 0)
				{
					sendCommand("  ("+add+")", ss.getWords());
				}
			}
		}
	}




	// ----------------------------------------------------------------------
	// ---( INNER CLASS PlayerBot )---
	// ----------------------------------------------------------------------
	private class PlayerBot extends ScrambleLink implements Runnable
	{
		private double tuneMiss;	// % chance it will not look at one of your words
		private int tuneTimeMax;	// max wait time between guesses
		private int tuneTimeMin;	// min wait time between guesses
		private int tuneWordWeight;	// wait less this time for each player word
		private int tunePoolWeight;	// wait less this time for each pool letter
		private int tuneThrowWait;	// time to sleep if < tuneThrowMin
		private int tuneThrowMin;	// min time since last throw
		private int tuneMaxWordLen;	// will not make words longer than this
		private int tuneMinPoolSize;	// pool must be this size for take
		private int tuneGameLevel; // more like game level
		private String name;
		private String game;
		private String pool;
		private Map players;
		private Map pwords;
		private WordSource source;
		private int readyWait;
		private int level;
		private long lastThrow;
		private boolean inGame;
		private boolean inPlay;
		private Random r;

		public void debug(String msg)
		{
			Scramble.this.debug("<"+game+"> "+name+" - "+msg);
		}

		PlayerBot(int level, String name, String game) throws Exception
		{
			this.name = name;
			this.game = game;
			this.players = new HashMap();
			this.pwords = new HashMap();
			this.source = (WordSource) dict.get("default");
			this.readyWait = 0;
			this.pool = "";
			this.level = level;
			this.inGame = false;
			this.inPlay = false;
			this.r = new Random(System.currentTimeMillis());
			startBot();
		}

		public void startBot() throws IOException, InterruptedException {
			setup(new Socket(gameHost, sport));
			setLevel(level);
			new Thread(this).start();
		}
		
		public void linkDown(boolean onError)
		{
			super.linkDown(onError);
			for (int i=0; i<5; i++) {
				try {
					startBot();
				} catch (Exception ex) {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				return;
			}
		}
		
		public void setLevel(int level)
		{
			switch(level)
			{
				case BOT_LEVEL3:
					tuneMiss = 0.75;
					tuneTimeMax = 6000;
					tuneTimeMin = 1000;
					tuneWordWeight = 200;
					tunePoolWeight = 200;
					tuneThrowWait = 1000;
					tuneThrowMin = 1000;
					tuneMaxWordLen = 7;
					tuneMinPoolSize = 4;
					tuneGameLevel = 2;
					break;
				case BOT_LEVEL2:
					tuneMiss = 0.80;
					tuneTimeMax = 7000;
					tuneTimeMin = 1000;
					tuneWordWeight = 150;
					tunePoolWeight = 150;
					tuneThrowWait = 1500;
					tuneThrowMin = 1000;
					tuneMaxWordLen = 6;
					tuneMinPoolSize = 5;
					tuneGameLevel = 1;
					break;
				default:
					tuneMiss = 0.85;
					tuneTimeMax = 7500;
					tuneTimeMin = 1000;
					tuneWordWeight = 100;
					tunePoolWeight = 100;
					tuneThrowWait = 2000;
					tuneThrowMin = 1500;
					tuneMaxWordLen = 5;
					tuneMinPoolSize = 5;
					tuneGameLevel = 0;
					break;
			}
		}

		public void run()
		{
			Thread.currentThread().setName("PlayerBot "+name+":"+game);
			while (getSocket().isConnected())
			{
				try
				{
					if (System.currentTimeMillis() - lastThrow < tuneThrowMin)
					{
						Thread.sleep(tuneThrowWait);
					}
					int psize = (pool != null ? pool.length() : 0);
					int words = 0;
					for (Iterator i = pwords.values().iterator(); i.hasNext(); ) {
						List list = (List)i.next();
						words += list.size();
					}
					int sub = Math.min((tuneTimeMax - tuneTimeMin), (psize * tunePoolWeight + words * tuneWordWeight));
					Thread.sleep(tuneTimeMax - sub);
					//debug("sleep : "+(tuneTimeMax - sub));
					if (inPlay && pool != null && players.size() > 0)
					{
						lookForWords();
					}
				}
				catch (Exception ex)
				{
					ex.printStackTrace();
				}
			}
		}

		public void lookForWords()
		{
			long start = System.currentTimeMillis();
			boolean guess = false;
			// guess from pool only
			if (pool.length() >= tuneMinPoolSize)
			{
				byte b[] = new byte[3 + (r.nextDouble() > 0.75 ? 1 : 0)];
				byte bp[] = pool.getBytes();
				for (int j=0; !guess && j<bp.length/4; j++)
				{
					for (int i=0; i<b.length; )
					{
						int pos = (int)(r.nextDouble() * bp.length);
						if (bp[pos] != 0)
						{
							b[i++] = bp[pos];
							bp[pos] = 0;
						}
					}
					SolutionSet ss = source.getWords(b);
					String words[] = ss.getWords();
					if (words.length > 0)
					{
//debug("guess from pool : "+toString(words));
						sendTake(words[(int)(r.nextDouble() * words.length)]);
						guess = true;
					}
				}
			}
			// guess from players stash or stash + pool (50/50)
			if (!guess)
			{
				byte bp[] = pool.getBytes();
				for (Iterator e = pwords.values().iterator(); !guess && e.hasNext(); )
				{
					if (r.nextDouble() < tuneMiss)
					{
						continue;
					}
					List v = (List) e.next();
					// will not take player's only word
					if (v.size() <= 1)
					{
						continue;
					}
					String word = (String)v.get((int)(r.nextDouble() * v.size()));
					if (r.nextDouble() > 0.5)
					{
						SolutionSet ss = source.getWords(word);
						String words[] = ss.getWords();
						if (words.length > 0)
						{
//debug("guess word ("+word+") = "+toString(words));
							sendTake(words[(int)(r.nextDouble() * words.length)]);
							guess = true;
						}
					}
					else
					{
						if (word.length() >= tuneMaxWordLen)
						{
							continue;
						}
						byte wb[] = word.getBytes();
						byte b[] = new byte[wb.length + 1];
						System.arraycopy(wb,0,b,0,wb.length);
						for (int i=0; i<bp.length && !guess; i++)
						{
							if (r.nextDouble() < tuneMiss)
							{
								continue;
							}
							b[wb.length] = bp[i];
							SolutionSet ss = source.getWords(b);
							String words[] = ss.getWords();
							if (words.length > 0)
							{
//debug("guess word ("+word+") + pool ("+new String(bp)+") = "+toString(words));
								sendTake(words[(int)(r.nextDouble() * words.length)]);
								guess = true;
							}
						}
					}
				}
			}
			long end = System.currentTimeMillis();
			if (end-start > 50)
			{
				debug("guessing words from : "+pool+" took "+(end-start)+"ms");
			}
		}

		public void CMD_hello(String args[])
		{
			sendHello();
			sendName(name);
		}

		public void CMD_ping(String args[])
		{
			sendPing();
		}

		public void CMD_take(String args[])
		{
			String user = args[0];
			String word = args[1];
			List v = (List) pwords.get(user);
			if (v == null)
			{
				v = new LinkedList();
				pwords.put(user, v);
			}
			v.add(word);
			// maybe I steal back :) -- even from myself
			//if (!user.equals(name) && (r.nextDouble() < 0.10 * (double)tuneGameLevel))
			if ((r.nextDouble() < 0.10 * (double)tuneGameLevel))
			{
				try { Thread.sleep((long)(500.0 + 1000.0 * r.nextDouble())); } catch (Exception ex) { ex.printStackTrace(); }
				long start = System.currentTimeMillis();
				SolutionSet ss = source.getWords(word);
				String words[] = ss.getWords();
				if (words.length > 1)
				{
//debug("retake guess word ("+word+") = "+toString(words));
					sendTake(words[(int)(r.nextDouble() * words.length)]);
				}
				else
				{
					boolean guess = false;
					byte bp[] = pool.getBytes();
					byte wb[] = word.getBytes();
					byte b[] = new byte[wb.length + 1];
					System.arraycopy(wb,0,b,0,wb.length);
					for (int i=0; i<bp.length && !guess; i++)
					{
						if (r.nextDouble() < tuneMiss)
						{
							continue;
						}
						b[wb.length] = bp[i];
						ss = source.getWords(b);
						words = ss.getWords();
//debug("retake guess word ("+word+") + pool ("+new String(bp)+") = "+toString(words));
						if (words.length > 0)
						{
							sendTake(words[(int)(r.nextDouble() * words.length)]);
							guess = true;
						}
					}
				}
				long end = System.currentTimeMillis();
				debug("retake guess time was : "+(end-start)+"ms");
			}
		}

		public void CMD_lose(String args[])
		{
			String user = args[0];
			String word = args[1];
			List v = (List) pwords.get(user);
			if (v == null)
			{
				v = new LinkedList();
				pwords.put(user, v);
			}
			else
			{
				if (!v.remove(word))
				{
					debug("BOT unable to remove '"+word+"' from vector");
				}
			}
		}

		public void CMD_name(String args[])
		{
			if (args[0].equals(name))
			{
				newGame(game, tuneGameLevel, tuneGameLevel);
//				sendJoin(game);
			}
		}

		public void CMD_join(String args[])
		{
			if (inGame && !args[0].equals(name))
			{
				sendChatTo(args[0], "Welcome. I'm the Bot hosting this game. When you're ready to play click 'Start Game'. If you need help, type '/help'.");
				players.put(args[0], "false");
				readyWait++;
			}
		}

		public void CMD_message(String args[])
		{
		}

		public void CMD_chat(String args[])
		{
			if (args.length > 1 && !args[0].equals(name))
			{
				String said = args[1].toLowerCase();
				if (said.equals("help"))
				{
					sendChat("Tune my behavior with the commands: '/nicer', '/harder', '/spank me' or '/default'.  Nicer and harder are cumulative.");
				}
				else
				if (said.equals("nicer"))
				{
					tuneTimeMax = Math.min(10000, tuneTimeMax+500);
					tuneTimeMin = Math.min(5000, tuneTimeMin+100);
					tuneWordWeight = Math.min(500, tuneWordWeight+25);
					tunePoolWeight = Math.min(500, tunePoolWeight+25);
					tuneMiss = Math.min(1.0, tuneMiss + 0.05);
					tuneMaxWordLen = 5;
					sendChat("ok, I'll play nicer");
				}
				else
				if (said.equals("harder"))
				{
					tuneTimeMax = Math.max(3000, tuneTimeMax-500);
					tuneTimeMin = Math.max(1000, tuneTimeMin-100);
					tuneWordWeight = Math.max(50, tuneWordWeight-25);
					tunePoolWeight = Math.max(50, tunePoolWeight-25);
					tuneMiss = Math.max(0.25, tuneMiss - 0.05);
					tuneMaxWordLen = 6;
					sendChat("there ya go!");
				}
				else
				if (said.equals("spank me"))
				{
					tuneTimeMax = 3000;
					tuneTimeMin = 1000;
					tuneWordWeight = 50;
					tunePoolWeight = 50;
					tuneMiss = 0.25;
					sendChat("hang on to your shorts");
					tuneMaxWordLen = 8;
				}
				else
				if (said.equals("default"))
				{
					setLevel(level);
					sendChat("using default settings");
				}
				else
				if (said.equals("??status??"))
				{
					debug("game("+game+") pool("+pool+") players("+players.keySet()+") source("+source+") ready("+readyWait+") game("+inGame+") play("+inPlay+")");
				}
				else
				if (said.equals("??reset??"))
				{
					sendReady(false);
					inPlay = false;
					readyWait = 0;
				}
				else
				if (said.equals("??die??")) 
				{
					linkDown(false);
				}
			}
		}

		public void CMD_pool(String args[])
		{
			this.pool = (args != null && args.length > 0 ? args[0] : "");
			lastThrow = System.currentTimeMillis();
		}

		public void CMD_newgame(String args[])
		{
			this.pool = "";
			sendOther("notimeout");
			inGame = true;
			setLevel(level);
		}

		public void CMD_games(String args[])
		{
		}

		public void CMD_timer(String args[])
		{
		}

		public void CMD_ready(String args[])
		{
			String pl = args[0];
			if (pl.equals(name))
			{
				return;
			}
			String nstat = args[1];
			String ostat = (String) players.get(pl);
			if (ostat != null)
			{
				if (!nstat.equals(ostat))
				{
					readyWait += (nstat.equals("false") ? 1 : -1);
					readyWait = Math.max(0,readyWait);
				}
				players.put(pl, nstat);
			}
			if (!inPlay && readyWait == 0)
			{
				sendReady(true);
				inPlay = true;
			}
			if (inPlay && readyWait == players.size()) {
				sendReady(false);
				inPlay = false;
			}
		}

		public void CMD_leave(String args[])
		{
			String user = args[0];
			List v = (List) pwords.get(user);
			if (v != null)
			{
				pwords.remove(user);
			}
			String ostat = (String) players.remove(user);
			if (ostat != null && ostat.equals("false"))
			{
				readyWait--;
			}
			players.remove(user);
			if (readyWait == 0)
			{
				sendReady(players.size() > 0);
				inPlay = players.size() > 0;
			}
		}

		public void CMD_player(String args[])
		{
		}

		public void CMD_gameover(String args[])
		{
			inPlay = false;
			pwords.clear();
		}

		public void handleCommand(String args[])
		{
			debug("misc : "+toString(args));
		}
	}

}
