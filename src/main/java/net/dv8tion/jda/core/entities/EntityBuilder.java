/*
 *     Copyright 2015-2017 Austin Keener & Michael Ritter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.core.entities;

import gnu.trove.map.TLongObjectMap;
import net.dv8tion.jda.bot.entities.ApplicationInfo;
import net.dv8tion.jda.bot.entities.impl.ApplicationInfoImpl;
import net.dv8tion.jda.client.entities.*;
import net.dv8tion.jda.client.entities.impl.*;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.Region;
import net.dv8tion.jda.core.entities.MessageEmbed.*;
import net.dv8tion.jda.core.entities.impl.*;
import net.dv8tion.jda.core.exceptions.AccountTypeException;
import net.dv8tion.jda.core.handle.GuildMembersChunkHandler;
import net.dv8tion.jda.core.handle.ReadyHandler;
import net.dv8tion.jda.core.requests.WebSocketClient;
import net.dv8tion.jda.core.utils.MiscUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.awt.Color;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class EntityBuilder
{
    public static final String MISSING_CHANNEL = "MISSING_CHANNEL";
    public static final String MISSING_USER = "MISSING_USER";

    private static final Pattern channelMentionPattern = Pattern.compile("<#(\\d+)>");

    protected final JDAImpl api;
    protected final TLongObjectMap<JSONObject> cachedGuildJsons = MiscUtil.newLongMap();
    protected final TLongObjectMap<Consumer<Guild>> cachedGuildCallbacks = MiscUtil.newLongMap();

    public EntityBuilder(JDA api)
    {
        this.api = (JDAImpl) api;
    }

    public SelfUser createSelfUser(JSONObject self)
    {
        SelfUserImpl selfUser = ((SelfUserImpl) api.getSelfUser());
        if (selfUser == null)
        {
            final long id = self.getLong("id");
            selfUser = new SelfUserImpl(id, api);
            api.setSelfUser(selfUser);
        }
        if (!api.getUserMap().containsKey(selfUser.getIdLong()))
        {
            api.getUserMap().put(selfUser.getIdLong(), selfUser);
        }
        return (SelfUser) selfUser
                .setVerified(self.getBoolean("verified"))
                .setMfaEnabled(self.getBoolean("mfa_enabled"))
                .setEmail(!self.isNull("email") ? self.getString("email") : null)
                .setName(self.getString("username"))
                .setDiscriminator(self.getString("discriminator"))
                .setAvatarId(self.isNull("avatar") ? null : self.getString("avatar"))
                .setBot(self.has("bot") && self.getBoolean("bot"));
    }

    public void createGuildFirstPass(JSONObject guild, Consumer<Guild> secondPassCallback)
    {
        final long id = guild.getLong("id");
        GuildImpl guildObj = ((GuildImpl) api.getGuildMap().get(id));
        if (guildObj == null)
        {
            guildObj = new GuildImpl(api, id);
            api.getGuildMap().put(id, guildObj);
        }
        if (guild.has("unavailable") && guild.getBoolean("unavailable"))
        {
            guildObj.setAvailable(false);
            //This is used for when GuildCreateHandler receives a guild that is currently unavailable. During normal READY
            // loading for bots (which unavailable is always true) the secondPassCallback parameter will always
            // be null.
            if (secondPassCallback != null)
                secondPassCallback.accept(guildObj);
            api.getGuildLock().lock(id);
            return;
        }

        //If we make it to here, the Guild is available. This means 1 of 2 things:
        //Either:
        // 1) This is Guild provided during READY for a Client account
        // 2) This is a Guild received from GuildCreateHandler from a GUILD_CREATE event.
        //      This could be triggered by joining a guild or due to discord finally
        //      providing us with Guild information about a previously unavailable guild.
        //      Whether it was unavailable due to Bot READY unavailability or due to an
        //      outage within discord matters now.
        //
        // Either way, we now have enough information to fill in the general information about the Guild.
        // This does NOT necessarily mean that we have all information to complete the guild.
        // For Client accounts, we will also need to use op 12 (GUILD_SYNC) to get all presences of online users because
        // discord only provides Online users that we have an open PM channel with or are friends with for Client accounts.
        // On larger guilds we will still need to request all users using op 8 (GUILD_MEMBERS_CHUNK).
        //
        // The code below takes the information we -do- have and starts to fill in the Guild. It won't create anything
        // that might rely on Users that we don't have due to needing the GUILD_MEMBERS_CHUNK
        // This includes making VoiceStatus and PermissionOverrides

        guildObj.setAvailable(true)
                .setIconId(guild.isNull("icon") ? null : guild.getString("icon"))
                .setSplashId(guild.isNull("splash") ? null : guild.getString("splash"))
                .setRegion(Region.fromKey(guild.getString("region")))
                .setName(guild.getString("name"))
                .setAfkTimeout(Guild.Timeout.fromKey(guild.getInt("afk_timeout")))
                .setVerificationLevel(Guild.VerificationLevel.fromKey(guild.getInt("verification_level")))
                .setDefaultNotificationLevel(Guild.NotificationLevel.fromKey(guild.getInt("default_message_notifications")))
                .setRequiredMFALevel(Guild.MFALevel.fromKey(guild.getInt("mfa_level")));

        JSONArray roles = guild.getJSONArray("roles");
        for (int i = 0; i < roles.length(); i++)
        {
            Role role = createRole(roles.getJSONObject(i), guildObj.getIdLong());
            guildObj.getRolesMap().put(role.getIdLong(), role);
            if (role.getIdLong() == guildObj.getIdLong())
                guildObj.setPublicRole(role);
        }

        if (!guild.isNull("emojis"))
        {
            JSONArray array = guild.getJSONArray("emojis");
            TLongObjectMap<Emote> emoteMap = guildObj.getEmoteMap();
            for (int i = 0; i < array.length(); i++)
            {
                JSONObject object = array.getJSONObject(i);
                JSONArray emoteRoles = object.getJSONArray("roles");
                final long emoteId = object.getLong("id");

                EmoteImpl emoteObj = new EmoteImpl(emoteId, guildObj);
                Set<Role> roleSet = emoteObj.getRoleSet();

                for (int j = 0; j < emoteRoles.length(); j++)
                    roleSet.add(guildObj.getRoleById(emoteRoles.getString(j)));
                emoteMap.put(emoteId, emoteObj
                        .setName(object.getString("name"))
                        .setManaged(object.getBoolean("managed")));
            }
        }

        if (guild.has("members"))
        {
            JSONArray members = guild.getJSONArray("members");
            createGuildMemberPass(guildObj, members);
        }

        //This could be null for Client accounts. Will be fixed by GUILD_SYNC
        Member owner = guildObj.getMemberById(guild.getLong("owner_id"));
        if (owner != null)
            guildObj.setOwner(owner);

        if (guild.has("presences"))
        {
            JSONArray presences = guild.getJSONArray("presences");
            for (int i = 0; i < presences.length(); i++)
            {
                JSONObject presence = presences.getJSONObject(i);
                final long userId = presence.getJSONObject("user").getLong("id");
                MemberImpl member = (MemberImpl) guildObj.getMembersMap().get(userId);

                if (member == null)
                    WebSocketClient.LOG.debug("Received a ghost presence in GuildFirstPass! Guild: " + guildObj + " UserId: " + userId);
                else
                    createPresence(member, presence);
            }
        }

        if (guild.has("channels"))
        {
            JSONArray channels = guild.getJSONArray("channels");

            for (int i = 0; i < channels.length(); i++)
            {
                JSONObject channel = channels.getJSONObject(i);
                ChannelType type = ChannelType.fromId(channel.getInt("type"));
                if (type == ChannelType.TEXT)
                {
                    TextChannel newChannel = createTextChannel(channel, guildObj.getIdLong(), false);
                    if (newChannel.getIdLong() == guildObj.getIdLong())
                        guildObj.setPublicChannel(newChannel);
                }
                else if (type == ChannelType.VOICE)
                {
                    VoiceChannel newChannel = createVoiceChannel(channel, guildObj.getIdLong(), false);
                    if (!guild.isNull("afk_channel_id")
                            && newChannel.getId().equals(guild.getString("afk_channel_id")))
                        guildObj.setAfkChannel(newChannel);
                }
                else
                    WebSocketClient.LOG.fatal("Received a channel for a guild that isn't a text or voice channel. JSON: " + channel);
            }
        }

        //If the members that we were provided with (and loaded above) were not all of the
        //  the members in this guild, then we need to request more users from Discord using
        //  op 9 (GUILD_MEMBERS_CHUNK). To do so, we will cache the guild's JSON so we can properly
        //  load stuff that relies on Users like Channels, PermissionOverrides and VoiceStatuses
        //  after we have the rest of the users. We will request the GUILD_MEMBERS_CHUNK information
        //  which will be sent from discord over the main Websocket and will be handled by
        //  GuildMemberChunkHandler. After the handler has received all users as determined by the
        //  value set using `setExpectedGuildMembers`, it will do one of the following:
        //    1) If this is a Bot account, immediately call EntityBuilder#createGuildSecondPass, thus finishing
        //        the Guild object creation process.
        //    2) If this is a Client account, it will request op 12 (GUILD_SYNC) to make sure we have all information
        //        about online users as GUILD_MEMBERS_CHUNK does not include presence information, and when loading the
        //        members from GUILD_MEMBERS_CHUNK, we assume they are offline. GUILD_SYNC makes sure that we mark them
        //        properly. After GUILD_SYNC is received by GuildSyncHandler, it will call EntityBuilder#createGuildSecondPass
        //
        //If we actually -did- get all of the users needed, then we don't need to Chunk. Furthermore,
        // we don't need to use GUILD_SYNC because we always get presences with users thus we have all information
        // needed to guild the Guild. We will skip
        if (guild.getJSONArray("members").length() != guild.getInt("member_count"))
        {
            cachedGuildJsons.put(id, guild);
            cachedGuildCallbacks.put(id, secondPassCallback);

            GuildMembersChunkHandler handler = api.getClient().getHandler("GUILD_MEMBERS_CHUNK");
            handler.setExpectedGuildMembers(id, guild.getInt("member_count"));

            //If we are already past READY / RESUME, then chunk at runtime. Otherwise, pass back to the ReadyHandler
            // and let it send a burst chunk request.
            if (api.getClient().isReady())
            {
                if (api.getAccountType() == AccountType.CLIENT)
                {
                    JSONObject obj = new JSONObject()
                            .put("op", 12)
                            .put("guild_id", guildObj.getId());
                    api.getClient().chunkOrSyncRequest(obj);
                }
                JSONObject obj = new JSONObject()
                        .put("op", 8)
                        .put("d", new JSONObject()
                                .put("guild_id", id)
                                .put("query","")
                                .put("limit", 0)
                        );
                api.getClient().chunkOrSyncRequest(obj);
            }
            else
            {
                ReadyHandler readyHandler = api.getClient().getHandler("READY");
                readyHandler.acknowledgeGuild(guildObj, true, true, api.getAccountType() == AccountType.CLIENT);
            }

            api.getGuildLock().lock(id);
            return;
        }

        //As detailed in the comment above, if we've made it this far then we have all member information needed to
        // create the Guild. Thus, we fill in the remaining information, unlock the guild, and provide the guild
        // to the callback
        //This should only occur on small user count guilds.

        JSONArray channels = guild.getJSONArray("channels");
        createGuildChannelPass(guildObj, channels); //Actually creates PermissionOverrides

        JSONArray voiceStates = guild.getJSONArray("voice_states");
        createGuildVoiceStatePass(guildObj, voiceStates);

        api.getGuildLock().unlock(guildObj.getIdLong());
        if (secondPassCallback != null)
            secondPassCallback.accept(guildObj);
    }

    public void createGuildSecondPass(long guildId, List<JSONArray> memberChunks)
    {
        JSONObject guildJson = cachedGuildJsons.remove(guildId);
        Consumer<Guild> secondPassCallback = cachedGuildCallbacks.remove(guildId);
        GuildImpl guildObj = (GuildImpl) api.getGuildMap().get(guildId);

        if (guildObj == null)
            throw new IllegalStateException("Attempted to perform a second pass on an unknown Guild. Guild not in JDA " +
                    "mapping. GuildId: " + guildId);
        if (guildJson == null)
            throw new IllegalStateException("Attempted to perform a second pass on an unknown Guild. No cached Guild " +
                    "for second pass. GuildId: " + guildId);
        if (secondPassCallback == null)
            throw new IllegalArgumentException("No callback provided for the second pass on the Guild!");

        for (JSONArray chunk : memberChunks)
        {
            createGuildMemberPass(guildObj, chunk);
        }

        Member owner = guildObj.getMemberById(guildJson.getLong("owner_id"));
        if (owner != null)
            guildObj.setOwner(owner);

        if (guildObj.getOwner() == null)
            WebSocketClient.LOG.fatal("Never set the Owner of the Guild: " + guildObj.getId() + " because we don't have the owner User object! How?!");

        JSONArray channels = guildJson.getJSONArray("channels");
        createGuildChannelPass(guildObj, channels);

        JSONArray voiceStates = guildJson.getJSONArray("voice_states");
        createGuildVoiceStatePass(guildObj, voiceStates);

        secondPassCallback.accept(guildObj);
        api.getGuildLock().unlock(guildId);
    }

    public void handleGuildSync(GuildImpl guild, JSONArray members, JSONArray presences)
    {
        for (int i = 0; i < members.length(); i++)
        {
            JSONObject memberJson = members.getJSONObject(i);
            createMember(guild, memberJson);
        }

        for (int i = 0; i < presences.length(); i++)
        {
            JSONObject presenceJson = presences.getJSONObject(i);
            final long userId = presenceJson.getJSONObject("user").getLong("id");

            MemberImpl member = (MemberImpl) guild.getMembersMap().get(userId);
            if (member == null)
                WebSocketClient.LOG.fatal("Received a Presence for a non-existent Member when dealing with GuildSync!");
            else
                this.createPresence(member, presenceJson);
        }
    }

    private void createGuildMemberPass(GuildImpl guildObj, JSONArray members)
    {
        for (int i = 0; i < members.length(); i++)
        {
            JSONObject memberJson = members.getJSONObject(i);
            createMember(guildObj, memberJson);
        }
    }

    private void createGuildChannelPass(GuildImpl guildObj, JSONArray channels)
    {
        for (int i = 0; i < channels.length(); i++)
        {
            JSONObject channel = channels.getJSONObject(i);
            ChannelType type = ChannelType.fromId(channel.getInt("type"));
            Channel channelObj = null;
            if (type == ChannelType.TEXT)
            {
                channelObj = api.getTextChannelById(channel.getLong("id"));
            }
            else if (type == ChannelType.VOICE)
            {
                channelObj = api.getVoiceChannelById(channel.getLong("id"));
            }
            else
                WebSocketClient.LOG.fatal("Received a channel for a guild that isn't a text or voice channel (ChannelPass). JSON: " + channel);

            if (channelObj != null)
            {
                JSONArray permissionOverwrites = channel.getJSONArray("permission_overwrites");
                for (int j = 0; j < permissionOverwrites.length(); j++)
                {
                    try
                    {
                        createPermissionOverride(permissionOverwrites.getJSONObject(j), channelObj);
                    }
                    catch (IllegalArgumentException e)
                    {
                        //Caused by Discord not properly clearing PermissionOverrides when a Member leaves a Guild.
                        WebSocketClient.LOG.debug(e.getMessage() + ". Ignoring PermissionOverride.");
                    }
                }
            }
            else
            {
                throw new RuntimeException("Got permission_override for unknown channel with id: " + channel.getString("id"));
            }
        }
    }

    public void createGuildVoiceStatePass(GuildImpl guildObj, JSONArray voiceStates)
    {
        for (int i = 0; i < voiceStates.length(); i++)
        {
            JSONObject voiceStateJson = voiceStates.getJSONObject(i);
            final long userId = voiceStateJson.getLong("user_id");
            Member member = guildObj.getMembersMap().get(userId);
            if (member == null)
            {
                WebSocketClient.LOG.fatal("Received a VoiceState for a unknown Member! GuildId: "
                        + guildObj.getId() + " MemberId: " + voiceStateJson.getString("user_id"));
                continue;
            }

            final long channelId = voiceStateJson.getLong("channel_id");
            VoiceChannelImpl voiceChannel =
                    (VoiceChannelImpl) guildObj.getVoiceChannelMap().get(channelId);
            voiceChannel.getConnectedMembersMap().put(member.getUser().getIdLong(), member);

            GuildVoiceStateImpl voiceState = (GuildVoiceStateImpl) member.getVoiceState();
            voiceState.setSelfMuted(voiceStateJson.getBoolean("self_mute"))
                    .setSelfDeafened(voiceStateJson.getBoolean("self_deaf"))
                    .setGuildMuted(voiceStateJson.getBoolean("mute"))
                    .setGuildDeafened(voiceStateJson.getBoolean("deaf"))
                    .setSuppressed(voiceStateJson.getBoolean("suppress"))
                    .setSessionId(voiceStateJson.getString("session_id"))
                    .setConnectedChannel(voiceChannel);
        }
    }

    public User createFakeUser(JSONObject user, boolean modifyCache) { return createUser(user, true, modifyCache); }
    public User createUser(JSONObject user)     { return createUser(user, false, true); }
    private User createUser(JSONObject user, boolean fake, boolean modifyCache)
    {
        final long id = user.getLong("id");
        UserImpl userObj;

        userObj = (UserImpl) api.getUserMap().get(id);
        if (userObj == null)
        {
            userObj = (UserImpl) api.getFakeUserMap().get(id);
            if (userObj != null)
            {
                if (!fake && modifyCache)
                {
                    api.getFakeUserMap().remove(id);
                    userObj.setFake(false);
                    api.getUserMap().put(userObj.getIdLong(), userObj);
                    if (userObj.hasPrivateChannel())
                    {
                        PrivateChannelImpl priv = (PrivateChannelImpl) userObj.getPrivateChannel();
                        priv.setFake(false);
                        api.getFakePrivateChannelMap().remove(priv.getIdLong());
                        api.getPrivateChannelMap().put(priv.getIdLong(), priv);
                    }
                }
            }
            else
            {
                userObj = new UserImpl(id, api).setFake(fake);
                if (modifyCache)
                {
                    if (fake)
                        api.getFakeUserMap().put(id, userObj);
                    else
                        api.getUserMap().put(id, userObj);
                }
            }
        }

        return userObj
                .setName(user.getString("username"))
                .setDiscriminator(user.get("discriminator").toString())
                .setAvatarId(user.isNull("avatar") ? null : user.getString("avatar"))
                .setBot(user.has("bot") && user.getBoolean("bot"));
    }

    public Member createMember(GuildImpl guild, JSONObject memberJson)
    {
        User user = createUser(memberJson.getJSONObject("user"));
        MemberImpl member = (MemberImpl) guild.getMember(user);
        if (member == null)
        {
            member = new MemberImpl(guild, user);
            guild.getMembersMap().put(user.getIdLong(), member);
        }

        ((GuildVoiceStateImpl) member.getVoiceState())
            .setGuildMuted(memberJson.getBoolean("mute"))
            .setGuildDeafened(memberJson.getBoolean("deaf"));

        member.setJoinDate(OffsetDateTime.parse(memberJson.getString("joined_at")))
            .setNickname(memberJson.has("nick") && !memberJson.isNull("nick")
                ? memberJson.getString("nick")
                : null);

        JSONArray rolesJson = memberJson.getJSONArray("roles");
        for (int k = 0; k < rolesJson.length(); k++)
        {
            final long roleId = rolesJson.getLong(k);
            Role r = guild.getRolesMap().get(roleId);
            if (r == null)
            {
                WebSocketClient.LOG.debug("Received a Member with an unknown Role. MemberId: "
                        + member.getUser().getId() + " GuildId: " + guild.getId() + " roleId: " + roleId);
            }
            else
            {
                member.getRoleSet().add(r);
            }
        }

        return member;
    }

    //Effectively the same as createFriendPresence
    public void createPresence(Object memberOrFriend, JSONObject presenceJson)
    {
        if (memberOrFriend == null)
            throw new NullPointerException("Provided memberOrFriend was null!");

        JSONObject gameJson = presenceJson.isNull("game") ? null: presenceJson.getJSONObject("game");
        OnlineStatus onlineStatus = OnlineStatus.fromKey(presenceJson.getString("status"));
        Game game = null;

        if (gameJson != null && !gameJson.isNull("name"))
        {
            String gameName = gameJson.get("name").toString();
            String url = gameJson.isNull("url")
                    ? null
                    : gameJson.get("url").toString();

            Game.GameType gameType;
            try
            {
                gameType = gameJson.isNull("type")
                        ? Game.GameType.DEFAULT
                        : Game.GameType.fromKey(Integer.parseInt(gameJson.get("type").toString()));
            }
            catch (NumberFormatException e)
            {
                gameType = Game.GameType.DEFAULT;
            }

            game = new GameImpl(gameName, url, gameType);
        }
        if (memberOrFriend instanceof Member)
        {
            MemberImpl member = (MemberImpl) memberOrFriend;
            member.setOnlineStatus(onlineStatus);
            member.setGame(game);
        }
        else if (memberOrFriend instanceof Friend)
        {
            FriendImpl friend = (FriendImpl) memberOrFriend;
            friend.setOnlineStatus(onlineStatus);
            friend.setGame(game);

            OffsetDateTime lastModified = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(presenceJson.getLong("last_modified")),
                    TimeZone.getTimeZone("GMT").toZoneId());

            friend.setOnlineStatusModifiedTime(lastModified);
        }
        else
            throw new IllegalArgumentException("An object was provided to EntityBuilder#createPresence that wasn't a Member or Friend. JSON: " + presenceJson);
    }

    public TextChannel createTextChannel(JSONObject json, long guildId)
    {
        return createTextChannel(json, guildId, true);

    }
    public TextChannel createTextChannel(JSONObject json, long guildId, boolean guildIsLoaded)
    {
        final long id = json.getLong("id");
        TextChannelImpl channel = (TextChannelImpl) api.getTextChannelMap().get(id);
        if (channel == null)
        {
            GuildImpl guild = ((GuildImpl) api.getGuildMap().get(guildId));
            channel = new TextChannelImpl(id, guild);
            guild.getTextChannelsMap().put(id, channel);
            api.getTextChannelMap().put(id, channel);
        }

        if (!json.isNull("permission_overwrites") && guildIsLoaded)
        {
            JSONArray overrides = json.getJSONArray("permission_overwrites");
            for (int i = 0; i < overrides.length(); i++)
            {
                createPermissionOverride(overrides.getJSONObject(i), channel);
            }
        }

        return channel
                .setLastMessageId(json.isNull("last_message_id") ? -1 : json.getLong("last_message_id"))
                .setName(json.getString("name"))
                .setTopic(json.isNull("topic") ? "" : json.getString("topic"))
                .setRawPosition(json.getInt("position"));
    }

    public VoiceChannel createVoiceChannel(JSONObject json, long guildId)
    {
        return createVoiceChannel(json, guildId, true);
    }
    public VoiceChannel createVoiceChannel(JSONObject json, long guildId, boolean guildIsLoaded)
    {
        final long id = json.getLong("id");
        VoiceChannelImpl channel = ((VoiceChannelImpl) api.getVoiceChannelMap().get(id));
        if (channel == null)
        {
            GuildImpl guild = (GuildImpl) api.getGuildMap().get(guildId);
            channel = new VoiceChannelImpl(id, guild);
            guild.getVoiceChannelMap().put(id, channel);
            api.getVoiceChannelMap().put(id, channel);
        }

        if (!json.isNull("permission_overwrites") && guildIsLoaded)
        {
            JSONArray overrides = json.getJSONArray("permission_overwrites");
            for (int i = 0; i < overrides.length(); i++)
            {
                createPermissionOverride(overrides.getJSONObject(i), channel);
            }
        }

        return channel
                .setName(json.getString("name"))
                .setRawPosition(json.getInt("position"))
                .setUserLimit(json.getInt("user_limit"))
                .setBitrate(json.getInt("bitrate"));
    }

    public PrivateChannel createPrivateChannel(JSONObject privatechat)
    {
        JSONObject recipient = privatechat.has("recipients") ? 
            privatechat.getJSONArray("recipients").getJSONObject(0) :
            privatechat.getJSONObject("recipient");
        final long userId = recipient.getLong("id");
        UserImpl user = ((UserImpl) api.getUserMap().get(userId));
        if (user == null)
        {   //The API can give us private channels connected to Users that we can no longer communicate with.
            // As such, make a fake user and fake private channel.
            user = (UserImpl) createFakeUser(recipient, true);
        }

        final long channelId = privatechat.getLong("id");
        PrivateChannelImpl priv = new PrivateChannelImpl(channelId, user)
                .setLastMessageId(privatechat.isNull("last_message_id") ? -1 : privatechat.getLong("last_message_id"));
        user.setPrivateChannel(priv);

        if (user.isFake())
        {
            priv.setFake(true);
            api.getFakePrivateChannelMap().put(channelId, priv);
        }
        else
            api.getPrivateChannelMap().put(channelId, priv);
        return priv;
    }

    public Role createRole(JSONObject roleJson, long guildId)
    {
        final long id = roleJson.getLong("id");
        GuildImpl guild = ((GuildImpl) api.getGuildMap().get(guildId));
        RoleImpl role = ((RoleImpl) guild.getRolesMap().get(id));
        if (role == null)
        {
            role = new RoleImpl(id, guild);
            guild.getRolesMap().put(id, role);
        }
        return role.setName(roleJson.getString("name"))
                .setRawPosition(roleJson.getInt("position"))
                .setRawPermissions(roleJson.getLong("permissions"))
                .setManaged(roleJson.getBoolean("managed"))
                .setHoisted(roleJson.getBoolean("hoist"))
                .setColor(roleJson.getInt("color") != 0 ? new Color(roleJson.getInt("color")) : null)
                .setMentionable(roleJson.has("mentionable") && roleJson.getBoolean("mentionable"));
    }

    public Message createMessage(JSONObject jsonObject) { return createMessage(jsonObject, false); }
    public Message createMessage(JSONObject jsonObject, boolean exceptionOnMissingUser)
    {
        final long channelId = jsonObject.getLong("channel_id");

        MessageChannel chan = api.getTextChannelById(channelId);
        if (chan == null)
            chan = api.getPrivateChannelById(channelId);
        if (chan == null)
            chan = api.getFakePrivateChannelMap().get(channelId);
        if (chan == null && api.getAccountType() == AccountType.CLIENT)
            chan = api.asClient().getGroupById(channelId);
        if (chan == null)
            throw new IllegalArgumentException(MISSING_CHANNEL);

        return createMessage(jsonObject, chan, exceptionOnMissingUser);
    }
    public Message createMessage(JSONObject jsonObject, MessageChannel chan, boolean exceptionOnMissingUser)
    {
        final long id = jsonObject.getLong("id");
        String content = jsonObject.isNull("content") ? "" : jsonObject.getString("content");

        JSONObject author = jsonObject.getJSONObject("author");
        final long authorId = author.getLong("id");
        final boolean fromWebhook = jsonObject.has("webhook_id");
        final boolean pinned = !jsonObject.isNull("pinned") && jsonObject.getBoolean("pinned");
        final boolean tts = !jsonObject.isNull("tts") && jsonObject.getBoolean("tts");
        final boolean mentionsEveryone = !jsonObject.isNull("mention_everyone") && jsonObject.getBoolean("mention_everyone");
        final OffsetDateTime editTime = jsonObject.isNull("edited_timestamp") ? null : OffsetDateTime.parse(jsonObject.getString("edited_timestamp"));
        final String nonce = jsonObject.isNull("nonce") ? null : jsonObject.get("nonce").toString();

        User user;
        switch (chan.getType())
        {
            case PRIVATE:
                if (authorId == api.getSelfUser().getIdLong())
                    user = api.getSelfUser();
                else
                    user = ((PrivateChannel) chan).getUser();
                break;
            case GROUP:
                user = api.getUserById(authorId);
                if (user == null)
                    user = api.getFakeUserMap().get(authorId);
                if (user == null && fromWebhook)
                    user = createFakeUser(author, false);
                if (user == null)
                {
                    if (exceptionOnMissingUser)
                        throw new IllegalArgumentException(MISSING_USER); // Specifically for MESSAGE_CREATE
                    else
                        user = createFakeUser(author, false); // Any other message creation
                }
                break;
            case TEXT:
                Guild guild = ((TextChannel) chan).getGuild();
                Member member = guild.getMemberById(authorId);
                user = member != null ? member.getUser() : null;
                if (user == null)
                {
                    if (fromWebhook || !exceptionOnMissingUser)
                        user = createFakeUser(author, false);
                    else
                        throw new IllegalArgumentException(MISSING_USER); // Specifically for MESSAGE_CREATE
                }
                break;
            default: throw new IllegalArgumentException("Invalid Channel for creating a Message [" + chan.getType() + ']');
        }

        final List<Message.Attachment> attachments;
        if (!jsonObject.isNull("attachments"))
        {
            JSONArray arr = jsonObject.getJSONArray("attachments");
            attachments = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++)
            {
                JSONObject obj = arr.getJSONObject(i);
                attachments.add(createMessageAttachment(obj));
            }
        }
        else
        {
            attachments = Collections.emptyList();
        }

        final List<MessageEmbed> embeds;
        if (!jsonObject.isNull("embeds"))
        {
            JSONArray arr = jsonObject.getJSONArray("embeds");
            embeds = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++)
            {
                JSONObject obj = arr.getJSONObject(i);
                embeds.add(createMessageEmbed(obj));
            }
        }
        else
        {
            embeds = Collections.emptyList();
        }

        List<MessageReaction> reactions;
        if (!jsonObject.isNull("reactions"))
        {
            JSONArray arr = jsonObject.getJSONArray("reactions");
            reactions = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++)
            {
                JSONObject obj = arr.getJSONObject(i);
                final MessageReaction reaction = createMessageReaction(chan, id, obj);
                reactions.add(reaction);
            }
        }
        else
        {
            reactions = Collections.emptyList();
        }

//        List<User> mentionUsers = null;
//        if (!jsonObject.isNull("mentions") && chan.getType() == ChannelType.TEXT)
//        {
//            JSONArray arr = jsonObject.getJSONArray("mentions");
//            TreeMap<Integer, User> tree = new TreeMap<>();
//            for (int i = 0; i < arr.length(); i++)
//            {
//                JSONObject obj = arr.getJSONObject(i);
//                long mentionId = obj.getLong("id");
//                User u = api.getUserById(mentionId);
//                if (u != null)
//                {
//                    //We do this to properly order the mentions. The array given by discord is out of order sometimes.
//
//                    int index = content.indexOf("<@" + mentionId + '>');
//                    if (index < 0) // nickname mention?
//                        index = content.indexOf("<@!" + mentionId + '>');
//                    tree.put(index, u);
//                }
//            }
//            mentionUsers = new ArrayList<>(tree.values());
//        }

//        List<Role> mentionRoles = null;
//        if (!jsonObject.isNull("mention_roles") && chan.getType() == ChannelType.TEXT)
//        {
//            TextChannel textChannel = (TextChannel) chan;
//            Guild guild = textChannel.getGuild();
//            JSONArray arr = jsonObject.getJSONArray("mention_roles");
//            TreeMap<Integer, Role> tree = new TreeMap<>();
//            for (int i = 0; i < arr.length(); i++)
//            {
//                long roleId = arr.getLong(i);
//                Role r = guild.getRoleById(roleId);
//                if (r != null)
//                {
//                    int index = content.indexOf("<@&" + roleId + '>');
//                    tree.put(index, r);
//                }
//            }
//            mentionRoles = new ArrayList<>(tree.values());
//        }

        return new MessageImpl(id, chan, MessageType.DEFAULT,
                fromWebhook, mentionsEveryone, tts, pinned,
                content, nonce, user, editTime, reactions, attachments, embeds);
    }

    public MessageReaction createMessageReaction(MessageChannel chan, long id, JSONObject obj)
    {
        JSONObject emoji = obj.getJSONObject("emoji");
        final Long emojiID = emoji.isNull("id") ? null : emoji.getLong("id");
        final String name = emoji.isNull("name") ? null : emoji.getString("name");
        final int count = obj.getInt("count");
        final boolean me = !obj.isNull("me") && obj.getBoolean("me");

        final MessageReaction.ReactionEmote reactionEmote;
        if (emojiID != null)
        {
            Emote emote = api.getEmoteById(emojiID);
            // creates fake emoji because no guild has this emoji id
            if (emote == null)
                emote = new EmoteImpl(emojiID, api).setName(name);
            reactionEmote = new MessageReaction.ReactionEmote(emote);
        }
        else
        {
            reactionEmote = new MessageReaction.ReactionEmote(name, null, api);
        }

        return new MessageReaction(chan, reactionEmote, id, me, count);
    }

    public Message.Attachment createMessageAttachment(JSONObject jsonObject)
    {
        final int width = jsonObject.isNull("width") ? 0 : jsonObject.getInt("width");
        final int height = jsonObject.isNull("height") ? 0 : jsonObject.getInt("height");
        final int size = jsonObject.getInt("size");
        final String url = jsonObject.isNull("url") ? null : jsonObject.getString("url");
        final String proxyUrl = jsonObject.isNull("proxy_url") ? null : jsonObject.getString("proxy_url");
        final String filename = jsonObject.getString("filename");
        final long id = jsonObject.getLong("id");
        return new Message.Attachment(id, url, proxyUrl, filename, size, height, width, api);
    }

    public MessageEmbed createMessageEmbed(JSONObject content)
    {
        if (content.isNull("type"))
            throw new JSONException("Encountered embed object with missing/null type field for Json: " + content);
        EmbedType type = EmbedType.fromKey(content.getString("type"));
        final String url = content.isNull("url") ? null : content.getString("url");
        final String title = content.isNull("title") ? null : content.getString("title");
        final String description = content.isNull("description") ? null : content.getString("description");
        final OffsetDateTime timestamp = content.isNull("timestamp")
                ? null : OffsetDateTime.parse(content.getString("timestamp"));
        final int rgb = content.isNull("color") ? 0 : content.getInt("color");
        final Color color = rgb == 0 ? null : new Color(rgb);

        final Thumbnail thumbnail;
        if (content.isNull("thumbnail"))
        {
            thumbnail = null;
        }
        else
        {
            JSONObject obj = content.getJSONObject("thumbnail");
            final String tUrl = obj.getString("url");
            final String proxyUrl = obj.getString("proxy_url");
            final int width = obj.getInt("width");
            final int height = obj.getInt("height");
            thumbnail = new Thumbnail(tUrl, proxyUrl, width, height);
        }

        final Provider provider;
        if (content.isNull("provider"))
        {
            provider = null;
        }
        else
        {
            JSONObject obj = content.getJSONObject("provider");
            final String name = obj.isNull("name") ? null : obj.getString("name");
            final String providerUrl = obj.isNull("url") ? null : obj.getString("url");
            provider = new Provider(name, providerUrl);
        }

        final AuthorInfo author;
        if (content.isNull("author"))
        {
            author = null;
        }
        else
        {
            JSONObject obj = content.getJSONObject("author");
            final String name = obj.isNull("name") ? null : obj.getString("name");
            final String authorUrl = obj.isNull("url") ? null : obj.getString("url");
            final String iconUrl = obj.isNull("icon_url") ? null : obj.getString("icon_url");
            final String proxyUrl = obj.isNull("proxy_icon_url") ? null : obj.getString("proxy_icon_url");
            author = new AuthorInfo(name, authorUrl, iconUrl, proxyUrl);
        }

        final VideoInfo video;
        if (content.isNull("video"))
        {
            video = null;
        }
        else
        {
            JSONObject obj = content.getJSONObject("video");
            final String videoUrl = obj.getString("url");
            final int width = obj.isNull("width") ? -1 : obj.getInt("width");
            final int height = obj.isNull("height") ? -1 : obj.getInt("height");
            video = new VideoInfo(videoUrl, width, height);
        }

        final Footer footer;
        if (content.isNull("footer"))
        {
            footer = null;
        }
        else
        {
            JSONObject obj = content.getJSONObject("footer");
            final String text = obj.isNull("text") ? null : obj.getString("text");
            final String iconUrl = obj.isNull("icon_url") ? null : obj.getString("icon_url");
            final String proxyUrl = obj.isNull("proxy_icon_url") ? null : obj.getString("proxy_icon_url");
            footer = new Footer(text, iconUrl, proxyUrl);
        }

        final ImageInfo image;
        if (content.isNull("image"))
        {
            image = null;
        }
        else
        {
            JSONObject obj = content.getJSONObject("image");
            final String imageUrl = obj.isNull("url") ? null : obj.getString("url");
            final String proxyUrl = obj.isNull("proxy_url") ? null : obj.getString("proxy_url");
            final int width = obj.isNull("width") ? -1 : obj.getInt("width");
            final int height = obj.isNull("height") ? -1 : obj.getInt("height");
            image = new ImageInfo(imageUrl, proxyUrl, width, height);
        }

        final List<Field> fields;
        if (content.isNull("fields"))
        {
            fields = Collections.emptyList();
        }
        else
        {
            JSONArray arr = content.getJSONArray("fields");
            fields = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++)
            {
                JSONObject obj = arr.getJSONObject(i);
                final String fieldName = obj.isNull("name") ? null : obj.getString("name");
                final String fieldValue = obj.isNull("value") ? null : obj.getString("value");
                final boolean inline = !obj.isNull("inline") && obj.getBoolean("inline");
                fields.add(new Field(fieldName, fieldValue, inline, false));
            }
        }

        return new MessageEmbedImpl(url, title, description, type, timestamp,
                color, thumbnail, provider, author, video, footer, image, fields);
    }

    public PermissionOverride createPermissionOverride(JSONObject override, Channel chan)
    {
        PermissionOverrideImpl permOverride = null;
        final long id = override.getLong("id");
        long allow = override.getLong("allow");
        long deny = override.getLong("deny");

        switch (override.getString("type"))
        {
            case "member":
                Member member = chan.getGuild().getMemberById(id);
                if (member == null)
                    throw new IllegalArgumentException("Attempted to create a PermissionOverride for a non-existent user. Guild: " + chan.getGuild() + ", Channel: " + chan + ", JSON: " + override);

                permOverride = (PermissionOverrideImpl) chan.getPermissionOverride(member);
                if (permOverride == null)
                {
                    permOverride = new PermissionOverrideImpl(chan, member.getUser().getIdLong(), member);
                    ((AbstractChannelImpl<?>) chan).getOverrideMap().put(member.getUser().getIdLong(), permOverride);
                }
                break;
            case "role":
                Role role = ((GuildImpl) chan.getGuild()).getRolesMap().get(id);
                if (role == null)
                    throw new IllegalArgumentException("Attempted to create a PermissionOverride for a non-existent role! JSON: " + override);

                permOverride = (PermissionOverrideImpl) chan.getPermissionOverride(role);
                if (permOverride == null)
                {
                    permOverride = new PermissionOverrideImpl(chan, role.getIdLong(), role);
                    ((AbstractChannelImpl<?>) chan).getOverrideMap().put(role.getIdLong(), permOverride);
                }
                break;
            default:
                throw new IllegalArgumentException("Provided with an unknown PermissionOverride type! JSON: " + override);
        }
        return permOverride.setAllow(allow)
                .setDeny(deny);
    }

    public Webhook createWebhook(JSONObject object)
    {
        final long id = object.getLong("id");
        final long guildId = object.getLong("guild_id");
        final long channelId = object.getLong("channel_id");
        String token = !object.isNull("token") ? object.getString("token") : null;

        TextChannel channel = api.getTextChannelById(channelId);
        if (channel == null)
            throw new NullPointerException(String.format("Tried to create Webhook for an un-cached TextChannel! WebhookId: %s ChannelId: %s GuildId: %s",
                    id, channelId, guildId));

        Object name = !object.isNull("name") ? object.get("name") : JSONObject.NULL;
        Object avatar = !object.isNull("avatar") ? object.get("avatar") : JSONObject.NULL;

        JSONObject fakeUser = new JSONObject()
                    .put("username", name)
                    .put("discriminator", "0000")
                    .put("id", id)
                    .put("avatar", avatar);
        User defaultUser = createFakeUser(fakeUser, false);

        JSONObject ownerJson = object.getJSONObject("user");
        final long userId = ownerJson.getLong("id");

        User owner = api.getUserById(userId);
        if (owner == null)
        {
            ownerJson.put("id", userId);
            owner = createFakeUser(ownerJson, false);
        }

        return new WebhookImpl(channel, id).setToken(token).setOwner(channel.getGuild().getMember(owner)).setUser(defaultUser);
    }

    public Relationship createRelationship(JSONObject relationshipJson)
    {
        if (api.getAccountType() != AccountType.CLIENT)
            throw new AccountTypeException(AccountType.CLIENT, "Attempted to create a Relationship but the logged in account is not a CLIENT!");

        RelationshipType type = RelationshipType.fromKey(relationshipJson.getInt("type"));
        User user;
        if (type == RelationshipType.FRIEND)
            user = createUser(relationshipJson.getJSONObject("user"));
        else
            user = createFakeUser(relationshipJson.getJSONObject("user"), true);

        Relationship relationship = api.asClient().getRelationshipById(user.getIdLong(), type);
        if (relationship == null)
        {
            switch (type)
            {
                case FRIEND:
                    relationship = new FriendImpl(user);
                    break;
                case BLOCKED:
                    relationship = new BlockedUserImpl(user);
                    break;
                case INCOMING_FRIEND_REQUEST:
                    relationship = new IncomingFriendRequestImpl(user);
                    break;
                case OUTGOING_FRIEND_REQUEST:
                    relationship = new OutgoingFriendRequestImpl(user);
                    break;
                default:
                    return null;
            }
            ((JDAClientImpl) api.asClient()).getRelationshipMap().put(user.getIdLong(), relationship);
        }
        return relationship;
    }

    public Group createGroup(JSONObject groupJson)
    {
        if (api.getAccountType() != AccountType.CLIENT)
            throw new AccountTypeException(AccountType.CLIENT, "Attempted to create a Group but the logged in account is not a CLIENT!");

        final long groupId = groupJson.getLong("id");
        JSONArray recipients = groupJson.getJSONArray("recipients");
        final long ownerId = groupJson.getLong("owner_id");
        String name = !groupJson.isNull("name") ? groupJson.getString("name") : null;
        String iconId = !groupJson.isNull("icon") ? groupJson.getString("icon") : null;
        long lastMessage = !groupJson.isNull("last_message_id") ? groupJson.getLong("last_message_id") : -1;

        GroupImpl group = (GroupImpl) api.asClient().getGroupById(groupId);
        if (group == null)
        {
            group = new GroupImpl(groupId, api);
            ((JDAClientImpl) api.asClient()).getGroupMap().put(groupId, group);
        }

        TLongObjectMap<User> groupUsers = group.getUserMap();
        groupUsers.put(api.getSelfUser().getIdLong(), api.getSelfUser());
        for (int i = 0; i < recipients.length(); i++)
        {
            JSONObject groupUser = recipients.getJSONObject(i);
            groupUsers.put(groupUser.getLong("id"), createFakeUser(groupUser, true));
        }

        User owner = api.getUserMap().get(ownerId);
        if (owner == null)
            owner = api.getFakeUserMap().get(ownerId);
        if (owner == null)
            throw new IllegalArgumentException("Attempted to build a Group, but could not find user by provided owner id." +
                    "This should not be possible because the owner should be IN the group!");

        return group
                .setOwner(owner)
                .setLastMessageId(lastMessage)
                .setName(name)
                .setIconId(iconId);
    }

    public Invite createInvite(JSONObject object)
    {
        final String code = object.getString("code");

        final JSONObject channelObject = object.getJSONObject("channel");
        final String channelTypeName = channelObject.getString("type");
        final User inviter = object.has("inviter") ? this.createFakeUser(object.getJSONObject("inviter"), false) : null;

        final ChannelType channelType = channelTypeName.equals("text")
            ? ChannelType.TEXT
            : channelTypeName.equals("voice")
                ? ChannelType.VOICE
                : ChannelType.UNKNOWN;
        final long channelId = channelObject.getLong("id");
        final String channelName = channelObject.getString("name");

        final Invite.Channel channel = new InviteImpl.ChannelImpl(channelId, channelName, channelType);

        final JSONObject guildObject = object.getJSONObject("guild");

        final String guildIconId = guildObject.isNull("icon") ? null : guildObject.getString("icon");
        final long guildId = guildObject.getLong("id");
        final String guildName = guildObject.getString("name");
        final String guildSplashId = guildObject.isNull("splash") ? null : guildObject.getString("splash");

        final Invite.Guild guild = new InviteImpl.GuildImpl(guildId, guildIconId, guildName, guildSplashId);

        final int maxAge;
        final int maxUses;
        final boolean temporary;
        final OffsetDateTime timeCreated;
        final int uses;
        final boolean expanded;

        if (object.has("max_uses"))
        {
            expanded = true;
            maxAge = object.getInt("max_age");
            maxUses = object.getInt("max_uses");
            uses = object.getInt("uses");
            temporary = object.getBoolean("temporary");
            timeCreated = OffsetDateTime.parse(object.getString("created_at"));
        }
        else
        {
            expanded = false;
            maxAge = -1;
            maxUses = -1;
            uses = -1;
            temporary = false;
            timeCreated = null;
        }

        return new InviteImpl(api, code, expanded, inviter, maxAge, maxUses, temporary, timeCreated, uses, channel, guild);
    }

    public void clearCache()
    {
        cachedGuildJsons.clear();
        cachedGuildCallbacks.clear();
    }

    public ApplicationInfo createApplicationInfo(JSONObject object)
    {
        final String description = object.getString("description");
        final boolean doesBotRequireCodeGrant = object.getBoolean("bot_require_code_grant");
        final String iconId = !object.isNull("icon") ? object.getString("icon") : null;
        final long id = object.getLong("id");
        final String name = object.getString("name");
        final boolean isBotPublic = object.getBoolean("bot_public");
        final User owner = createFakeUser(object.getJSONObject("owner"), false);

        return new ApplicationInfoImpl(api, description, doesBotRequireCodeGrant, iconId, id, isBotPublic, name, owner);
    }

    public Application createApplication(JSONObject object)
    {
        return new ApplicationImpl(api, object);
    }

    public AuthorizedApplication createAuthorizedApplication(JSONObject object)
    {
        final long authId = object.getLong("id");

        JSONArray scopeArray = object.getJSONArray("scopes");
        List<String> scopes = new ArrayList<>(scopeArray.length());
        for (int i = 0; i < scopeArray.length(); i++)
        {
            scopes.add(scopeArray.getString(i));
        }
        JSONObject application = object.getJSONObject("application");

        final String description = application.getString("description");
        final String iconId = application.has("icon") ? application.getString("icon") : null;
        final long id = application.getLong("id");
        final String name = application.getString("name");

        return new AuthorizedApplicationImpl(api, authId, description, iconId, id, name, scopes);
    }
}
