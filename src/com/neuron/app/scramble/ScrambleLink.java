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

public class ScrambleLink extends Link
{
	// ---( static fields )---
	public final static String HELLO       = "hello";		// link up
	public final static String UUID        = "uuid";		// exchange unique id
	public final static String PING        = "ping";		// try to raise client
	public final static String CHAT        = "chat";		// request message
	public final static String CHATTO      = "chatto";		// send targeted chat
	public final static String NAME        = "name";		// set player name
	public final static String DENY        = "deny";		// deny player name
	public final static String RESERVE     = "reserve";		// send name reserve token
	public final static String READY       = "ready";		// ready to play
	public final static String MESSAGE     = "message";		// broadcast
	public final static String TAKE        = "take";		// take word
	public final static String LOSE        = "lose";		// lose word
	public final static String POOL        = "pool";		// the letter pool
	public final static String PLAYER      = "player";		// player status
	public final static String JOIN        = "join";		// player joins game
	public final static String LEAVE       = "leave";		// leave game
	public final static String NEWGAME     = "newgame";		// game starts
	public final static String GAMEOVER    = "gameover";	// game ends
	public final static String GAMES       = "games";   	// request/get games
	public final static String TIMER       = "timer";   	// clock timer
	public final static String OTHER       = "other";   	// extension data

	public void sendHello()
	{
		sendCommand(HELLO);
	}
	
	public void sendUUID(String uuid)
	{
		sendCommand(UUID, uuid);
	}
	
	public void sendPing()
	{
		sendCommand(PING);
	}

	public void sendMessage(String msg)
	{
		sendCommand(MESSAGE, msg);
	}

	public void sendName(String name)
	{
		sendCommand(NAME, name);
	}

	public void sendDeny(String name)
	{
		sendCommand(DENY, name);
	}

	public void sendReserve(String token)
	{
		sendCommand(RESERVE, token);
	}

	public void sendJoin(String game)
	{
		sendCommand(JOIN, game);
	}

	public void sendChat(String chat)
	{
		sendCommand(CHAT, chat);
	}

	public void sendChatTo(String to, String chat)
	{
		sendCommand(CHATTO, new String[] {to, chat});
	}

	public void sendReady(boolean ready)
	{
		sendCommand(READY, (ready ? "true" : "false"));
	}

	public void sendTake(String word)
	{
		sendCommand(TAKE, word);
	}

	public void sendTimer(int sec)
	{
		sendCommand(TIMER, Integer.toString(sec));
	}

	public void sendOther(String other)
	{
		sendCommand(OTHER, other);
	}

	public void sendOther(String other[])
	{
		sendCommand(OTHER, other);
	}

	public void requestGames()
	{
		sendCommand(GAMES);
	}

	public void newGame(String name, int level, int speed)
	{
		sendCommand(NEWGAME, new String[] { name,Integer.toString(level), Integer.toString(speed) });
	}
}

