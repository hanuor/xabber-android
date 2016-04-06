/**
 * Copyright (c) 2013, Redsolution LTD. All rights reserved.
 *
 * This file is part of Xabber project; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License, Version 3.
 *
 * Xabber is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * along with this program. If not, see http://www.gnu.org/licenses/.
 */
package com.xabber.android.data.extension.muc;

import android.database.Cursor;

import com.xabber.android.R;
import com.xabber.android.data.Application;
import com.xabber.android.data.LogManager;
import com.xabber.android.data.NetworkException;
import com.xabber.android.data.OnLoadListener;
import com.xabber.android.data.account.AccountItem;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.ConnectionManager;
import com.xabber.android.data.connection.ConnectionThread;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.sqlite.RoomTable;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.UserJid;
import com.xabber.android.data.message.AbstractChat;
import com.xabber.android.data.message.ChatAction;
import com.xabber.android.data.message.MessageManager;
import com.xabber.android.data.notification.EntityNotificationProvider;
import com.xabber.android.data.notification.NotificationManager;
import com.xabber.android.data.roster.RosterManager;

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smackx.muc.HostedRoom;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.MultiUserChatException;
import org.jivesoftware.smackx.muc.MultiUserChatManager;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.muc.packet.MUCUser;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Manage multi user chats.
 * <p/>
 * Warning: We are going to remove SMACK components.
 *
 * @author alexander.ivanov
 */
public class MUCManager implements OnLoadListener, OnPacketListener {

    private final static MUCManager instance;

    static {
        instance = new MUCManager();
        Application.getInstance().addManager(instance);
    }

    private final EntityNotificationProvider<RoomInvite> inviteProvider;
    private final EntityNotificationProvider<RoomAuthorizationError> authorizationErrorProvider;

    private MUCManager() {
        inviteProvider = new EntityNotificationProvider<>(R.drawable.ic_stat_add_circle);
        authorizationErrorProvider = new EntityNotificationProvider<>(R.drawable.ic_stat_error);
    }

    public static MUCManager getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        Application.getInstance().runOnUiThread(new Runnable() {
            @Override
            public void run() {

                final Collection<RoomChat> roomChats = new ArrayList<>();
                final Collection<RoomChat> needJoins = new ArrayList<>();
                Cursor cursor = RoomTable.getInstance().list();
                try {
                    if (cursor.moveToFirst()) {
                        do {

                            try {
                                Resourcepart nickName = Resourcepart.from(RoomTable.getNickname(cursor));
                                AccountJid account = AccountJid.from(RoomTable.getAccount(cursor));
                                EntityBareJid room = JidCreate.entityBareFrom(RoomTable.getRoom(cursor));

                                RoomChat roomChat = new RoomChat(account, room,
                                        nickName, RoomTable.getPassword(cursor));
                                if (RoomTable.needJoin(cursor)) {
                                    needJoins.add(roomChat);
                                }
                                roomChats.add(roomChat);


                            } catch (XmppStringprepException e) {
                                e.printStackTrace();
                            }

                        } while (cursor.moveToNext());
                    }
                } finally {
                    cursor.close();
                }
                onLoaded(roomChats, needJoins);
            }
        });
    }

    private void onLoaded(Collection<RoomChat> roomChats, Collection<RoomChat> needJoins) {
        for (RoomChat roomChat : roomChats) {
            AbstractChat abstractChat = MessageManager.getInstance().getChat(
                    roomChat.getAccount(), roomChat.getUser());
            if (abstractChat != null) {
                MessageManager.getInstance().removeChat(abstractChat);
            }
            MessageManager.getInstance().addChat(roomChat);
            if (needJoins.contains(roomChat)) {
                roomChat.setState(RoomState.waiting);
            }
        }
        NotificationManager.getInstance().registerNotificationProvider(inviteProvider);
        NotificationManager.getInstance().registerNotificationProvider(authorizationErrorProvider);
    }

    /**
     * @return <code>null</code> if does not exists.
     */
    public RoomChat getRoomChat(AccountJid account, EntityBareJid room) {
        AbstractChat chat = MessageManager.getInstance().getChat(account, UserJid.from(room));
        if (chat != null && chat instanceof RoomChat) {
            return (RoomChat) chat;
        }
        return null;
    }

    public boolean hasRoom(AccountJid account, UserJid room) {
        return hasRoom(account, room.getJid().asEntityBareJidIfPossible());
    }

    /**
     * @return Whether there is such room.
     */
    public boolean hasRoom(AccountJid account, EntityBareJid room) {
        return getRoomChat(account, room) != null;
    }

    public boolean isMucPrivateChat(AccountJid account, UserJid user) {
        return hasRoom(account, user.getJid().asEntityBareJidIfPossible())
                && user.getJid().getResourceOrNull() != null;
    }

    public Resourcepart getNickname(AccountJid account, EntityBareJid room) {
        RoomChat roomChat = getRoomChat(account, room);
        if (roomChat == null) {
            return Resourcepart.EMPTY;
        }
        return roomChat.getNickname();
    }

    /**
     * @param account
     * @param room
     * @return password or empty string if room does not exists.
     */
    public String getPassword(AccountJid account, EntityBareJid room) {
        RoomChat roomChat = getRoomChat(account, room);
        if (roomChat == null) {
            return "";
        }
        return roomChat.getPassword();
    }

    /**
     * @return list of occupants or empty list.
     */
    public Collection<Occupant> getOccupants(AccountJid account, EntityBareJid room) {
        RoomChat roomChat = getRoomChat(account, room);
        if (roomChat == null) {
            return Collections.emptyList();
        }
        return roomChat.getOccupants();
    }

    /**
     * @return <code>null</code> if there is no such invite.
     */
    public RoomInvite getInvite(AccountJid account, EntityBareJid room) {
        return inviteProvider.get(account, UserJid.from(room));
    }

    public void removeInvite(RoomInvite abstractRequest) {
        inviteProvider.remove(abstractRequest);
    }

    public void removeRoom(final AccountJid account, final EntityBareJid room) {
        removeInvite(getInvite(account, room));
        RoomChat roomChat = getRoomChat(account, room);
        if (roomChat == null) {
            return;
        }
        leaveRoom(account, room);
        MessageManager.getInstance().removeChat(roomChat);
        RosterManager.onContactChanged(account, UserJid.from(room));
        Application.getInstance().runInBackground(new Runnable() {
            @Override
            public void run() {
                RoomTable.getInstance().remove(account.toString(), room.toString());
            }
        });
    }

    /**
     * Creates or updates existed room.
     *
     */
    public void createRoom(AccountJid account, EntityBareJid room, Resourcepart nickname,
                           String password, boolean join) {
        removeInvite(getInvite(account, room));
        AbstractChat chat = MessageManager.getInstance().getChat(account, UserJid.from(room));
        RoomChat roomChat;
        if (chat == null || !(chat instanceof RoomChat)) {
            if (chat != null) {
                MessageManager.getInstance().removeChat(chat);
            }
            roomChat = new RoomChat(account, room, nickname, password);
            MessageManager.getInstance().addChat(roomChat);
        } else {
            roomChat = (RoomChat) chat;
            roomChat.setNickname(nickname);
            roomChat.setPassword(password);
        }
        requestToWriteRoom(account, room, nickname, password, join);
        if (join) {
            joinRoom(account, room, true);
        }
    }

    private void requestToWriteRoom(final AccountJid account, final EntityBareJid room,
                                    final Resourcepart nickname, final String password, final boolean join) {
        Application.getInstance().runInBackground(new Runnable() {
            @Override
            public void run() {
                RoomTable.getInstance().write(account.toString(), room.toString(), nickname.toString(),
                        password, join);
            }
        });
    }

    /**
     * @return Whether room is disabled.
     */
    public boolean isDisabled(final AccountJid account, final EntityBareJid room) {
        RoomChat roomChat = getRoomChat(account, room);
        return roomChat == null || roomChat.getState() == RoomState.unavailable;
    }

    /**
     * @return Whether connected is establish or connection is in progress.
     */
    public boolean inUse(final AccountJid account, final EntityBareJid room) {
        RoomChat roomChat = getRoomChat(account, room);
        return roomChat != null && roomChat.getState().inUse();
    }

    /**
     * Requests to join to the room.
     *
     * @param requested Whether user request to join the room.
     */
    public void joinRoom(final AccountJid account, final EntityBareJid room, boolean requested) {
        final XMPPConnection xmppConnection;
        final RoomChat roomChat;
        final Resourcepart nickname;
        final String password;
        final Thread thread;
        roomChat = getRoomChat(account, room);
        if (roomChat == null) {
            Application.getInstance().onError(R.string.ENTRY_IS_NOT_FOUND);
            return;
        }
        RoomState state = roomChat.getState();
        if (state == RoomState.available || state == RoomState.occupation) {
            Application.getInstance().onError(R.string.ALREADY_JOINED);
            return;
        }
        if (state == RoomState.creating || state == RoomState.joining) {
            Application.getInstance().onError(R.string.ALREADY_IN_PROGRESS);
            return;
        }
        nickname = roomChat.getNickname();
        password = roomChat.getPassword();
        requestToWriteRoom(account, room, nickname, password, true);
        ConnectionThread connectionThread = AccountManager.getInstance()
                .getAccount(account).getConnectionThread();
        if (connectionThread == null) {
            Application.getInstance().onError(R.string.NOT_CONNECTED);
            return;
        }
        xmppConnection = connectionThread.getXMPPConnection();
        final MultiUserChat multiUserChat;
        try {
            multiUserChat = MultiUserChatManager.getInstanceFor(xmppConnection).getMultiUserChat(room);
        } catch (IllegalStateException e) {
            Application.getInstance().onError(R.string.NOT_CONNECTED);
            return;
        }
        roomChat.setState(RoomState.joining);
        roomChat.setMultiUserChat(multiUserChat);
        roomChat.setRequested(requested);
        thread = new Thread("Join to room " + room + " from " + account) {
            @Override
            public void run() {
                try {
                    if (roomChat.getMultiUserChat() != multiUserChat) {
                        return;
                    }
                    multiUserChat.join(nickname, password);
                    Application.getInstance().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (roomChat.getMultiUserChat() != multiUserChat) {
                                return;
                            }
                            if (roomChat.getState() == RoomState.joining) {
                                roomChat.setState(RoomState.occupation);
                            }
                            removeAuthorizationError(account, room);
                            RosterManager.onContactChanged(account, UserJid.from(room));
                        }
                    });
                    return;
                } catch (final XMPPException.XMPPErrorException e) {
                    Application.getInstance().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (roomChat.getMultiUserChat() != multiUserChat) {
                                return;
                            }
                            roomChat.setState(RoomState.error);
                            addAuthorizationError(account, room);

                            XMPPError xmppError = e.getXMPPError();

                                if (xmppError != null && xmppError.getCondition() == XMPPError.Condition.conflict) {
                                    Application.getInstance().onError(R.string.NICK_ALREADY_USED);
                                } else if (xmppError != null && xmppError.getCondition() == XMPPError.Condition.not_authorized) {
                                    Application.getInstance().onError(R.string.AUTHENTICATION_FAILED);
                                } else {
                                    Application.getInstance().onError(R.string.NOT_CONNECTED);
                                }
                            RosterManager.onContactChanged(account, UserJid.from(room));
                        }
                    });
                    return;
                } catch (IllegalStateException e) {
                } catch (Exception e) {
                    LogManager.exception(this, e);
                }
                Application.getInstance().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (roomChat.getMultiUserChat() != multiUserChat) {
                            return;
                        }
                        roomChat.setState(RoomState.waiting);
                        Application.getInstance().onError(R.string.NOT_CONNECTED);
                        RosterManager.onContactChanged(account, UserJid.from(room));
                    }
                });
            }
        };
        thread.setDaemon(true);
        thread.start();
    }

    public void leaveRoom(AccountJid account, EntityBareJid room) {
        final MultiUserChat multiUserChat;
        RoomChat roomChat = getRoomChat(account, room);
        if (roomChat == null) {
            return;
        }
        multiUserChat = roomChat.getMultiUserChat();
        roomChat.setState(RoomState.unavailable);
        roomChat.setRequested(false);
        roomChat.newAction(roomChat.getNickname(), null, ChatAction.leave);
        requestToWriteRoom(account, room, roomChat.getNickname(), roomChat.getPassword(), false);
        if (multiUserChat != null) {
            Thread thread = new Thread("Leave to room " + room + " from " + account) {
                @Override
                public void run() {
                    try {
                        multiUserChat.leave();
                    } catch (SmackException.NotConnectedException | InterruptedException e) {
                        LogManager.exception(this, e);
                    }
                }
            };
            thread.setDaemon(true);
            thread.start();
        }
        RosterManager.onContactChanged(account, UserJid.from(room));
    }

    @Override
    public void onStanza(ConnectionItem connection, Stanza stanza) {
        if (!(connection instanceof AccountItem)) {
            return;
        }
        AccountJid account = ((AccountItem) connection).getAccount();
        Jid from = stanza.getFrom();
        if (from == null || !(stanza instanceof Message)) {
            return;
        }
        Message message = (Message) stanza;
        if (message.getType() != Message.Type.normal && message.getType() != Message.Type.chat) {
            return;
        }
        MUCUser mucUser = MUCUser.from(stanza);
        if (mucUser == null || mucUser.getInvite() == null) {
            return;
        }

        RoomChat roomChat = getRoomChat(account, from.asEntityBareJidIfPossible());
        if (roomChat == null || !roomChat.getState().inUse()) {
            UserJid inviter = null;
            try {
                inviter = UserJid.from(mucUser.getInvite().getFrom());
            } catch (XmppStringprepException e) {
                LogManager.exception(this, e);
            }
            if (inviter == null) {
                inviter = UserJid.from(from);
            }
            inviteProvider.add(new RoomInvite(account, UserJid.from(from), inviter,
                            mucUser.getInvite().getReason(), mucUser.getPassword()), true);
        }
    }

    /**
     * Sends invitation.
     *
     * @throws NetworkException
     */
    public void invite(AccountJid account, EntityBareJid room, UserJid user) throws NetworkException {
        RoomChat roomChat = getRoomChat(account, room);
        if (roomChat == null || roomChat.getState() != RoomState.available) {
            Application.getInstance().onError(R.string.NOT_CONNECTED);
            return;
        }
        Message message = new Message(room);
        MUCUser mucUser = new MUCUser();
        MUCUser.Invite invite = new MUCUser.Invite();
        invite.setTo(user.toString());
        invite.setReason("");
        mucUser.setInvite(invite);
        message.addExtension(mucUser);
        ConnectionManager.getInstance().sendStanza(account, message);
        roomChat.putInvite(message.getStanzaId(), user);
        roomChat.newAction(roomChat.getNickname(), user.toString(), ChatAction.invite_sent);
    }

    public void removeAuthorizationError(AccountJid account, EntityBareJid room) {
        authorizationErrorProvider.remove(account, UserJid.from(room));
    }

    public void addAuthorizationError(AccountJid account, EntityBareJid room) {
        authorizationErrorProvider.add(new RoomAuthorizationError(account, UserJid.from(room)), null);
    }


    public interface HostedRoomsListener {
        void onHostedRoomsReceived(Collection<HostedRoom> hostedRooms);
    }

    public static void requestHostedRooms(final AccountJid account, final DomainBareJid serviceName, final HostedRoomsListener listener) {
        ConnectionThread connectionThread = AccountManager.getInstance().getAccount(account).getConnectionThread();
        if (connectionThread == null) {
            listener.onHostedRoomsReceived(null);
            return;
        }
        final XMPPConnection xmppConnection = connectionThread.getXMPPConnection();
        if (!xmppConnection.isAuthenticated()) {
            listener.onHostedRoomsReceived(null);
            return;
        }

        final Thread thread = new Thread("Get hosted rooms on server " + serviceName + " for account " + account) {
            @Override
            public void run() {
                Collection<HostedRoom> hostedRooms = null;

                try {
                    hostedRooms = MultiUserChatManager.getInstanceFor(xmppConnection).getHostedRooms(serviceName);
                } catch (SmackException.NoResponseException | XMPPException.XMPPErrorException
                        | SmackException.NotConnectedException | InterruptedException
                        | MultiUserChatException.NotAMucServiceException e) {
                    LogManager.exception(this, e);
                }

                final Collection<HostedRoom> finalHostedRooms = hostedRooms;
                Application.getInstance().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        listener.onHostedRoomsReceived(finalHostedRooms);
                    }
                });
            }
        };
        thread.start();

    }

    public interface RoomInfoListener {
        void onRoomInfoReceived(RoomInfo finalRoomInfo);
    }

    public static void requestRoomInfo(final AccountJid account, final EntityBareJid roomJid, final RoomInfoListener listener) {
        ConnectionThread connectionThread = AccountManager.getInstance().getAccount(account).getConnectionThread();
        if (connectionThread == null) {
            listener.onRoomInfoReceived(null);
            return;
        }
        final XMPPConnection xmppConnection = connectionThread.getXMPPConnection();
        if (!xmppConnection.isAuthenticated()) {
            listener.onRoomInfoReceived(null);
            return;
        }

        final Thread thread = new Thread("Get room " + roomJid + " info for account " + account) {
            @Override
            public void run() {
                RoomInfo roomInfo = null;

                try {
                    LogManager.i(MUCManager.class, "Requesting room info " + roomJid);
                    roomInfo = MultiUserChatManager.getInstanceFor(xmppConnection).getRoomInfo(roomJid);
                } catch (SmackException.NoResponseException | XMPPException.XMPPErrorException | SmackException.NotConnectedException | InterruptedException e) {
                    LogManager.exception(this, e);
                }

                final RoomInfo finalRoomInfo = roomInfo;
                Application.getInstance().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        listener.onRoomInfoReceived(finalRoomInfo);
                    }
                });
            }
        };
        thread.start();
    }
}
