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

package net.dv8tion.jda.core.entities.impl;

import net.dv8tion.jda.client.entities.Group;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.exceptions.PermissionException;
import net.dv8tion.jda.core.requests.RestAction;
import net.dv8tion.jda.core.utils.MiscUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.util.Args;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageImpl implements Message
{
    private final Object mutex = new Object();

    protected final JDAImpl api;
    protected final long id;
    protected final MessageType type;
    protected final MessageChannel channel;
    protected final boolean fromWebhook;
    protected final boolean mentionsEveryone;
    protected final boolean isTTS;
    protected final boolean pinned;
    protected final String content, nonce;
    protected final User author;
    protected final OffsetDateTime editedTime;
    protected final List<MessageReaction> reactions;
    protected final List<Attachment> attachments;
    protected final List<MessageEmbed> embeds;

    // LAZY EVALUATED
    protected String altContent = null;
    protected String strippedContent = null;

    protected List<User> userMentions = null;
    protected List<Emote> emoteMentions = null;
    protected List<Role> roleMentions = null;
    protected List<TextChannel> channelMentions = null;
    protected List<String> invites = null;

    public MessageImpl(long id, MessageChannel channel, MessageType type,
           boolean fromWebhook, boolean mentionsEveryone, boolean tts, boolean pinned,
           String content, String nonce, User author, OffsetDateTime editTime,
           List<MessageReaction> reactions, List<Attachment> attachments, List<MessageEmbed> embeds)
    {
        this.id = id;
        this.channel = channel;
        this.type = type;
        this.api = (channel != null) ? (JDAImpl) channel.getJDA() : null;
        this.fromWebhook = fromWebhook;
        this.mentionsEveryone = mentionsEveryone || (content != null && content.contains("@everyone"));
        this.isTTS = tts;
        this.pinned = pinned;
        this.content = content;
        this.nonce = nonce;
        this.author = author;
        this.editedTime = editTime;
        this.reactions = Collections.unmodifiableList(reactions);
        this.attachments = Collections.unmodifiableList(attachments);
        this.embeds = Collections.unmodifiableList(embeds);
    }

    @Override
    public JDA getJDA()
    {
        return api;
    }

    @Override
    public boolean isPinned()
    {
        return pinned;
    }

    @Override
    public RestAction<Void> pin()
    {
        return channel.pinMessageById(getIdLong());
    }

    @Override
    public RestAction<Void> unpin()
    {
        return channel.unpinMessageById(getIdLong());
    }

    @Override
    public RestAction<Void> addReaction(Emote emote)
    {
        Args.notNull(emote, "Emote");

        MessageReaction reaction = reactions.parallelStream()
                .filter(r -> Objects.equals(r.getEmote().getId(), emote.getId()))
                .findFirst().orElse(null);

        if (reaction == null)
        {
            checkFake(emote, "Emote");
            if (!emote.canInteract(api.getSelfUser(), channel))
                throw new IllegalArgumentException("Cannot react with the provided emote because it is not available in the current channel.");
        }
        else if (reaction.isSelf())
        {
            return new RestAction.EmptyRestAction<>(null);
        }

        return channel.addReactionById(getIdLong(), emote);
    }

    @Override
    public RestAction<Void> addReaction(String unicode)
    {
        Args.notEmpty(unicode, "Provided Unicode");

        MessageReaction reaction = reactions.parallelStream()
                .filter(r -> Objects.equals(r.getEmote().getName(), unicode))
                .findFirst().orElse(null);

        if (reaction != null && reaction.isSelf())
            return new RestAction.EmptyRestAction<>(null);

        return channel.addReactionById(getIdLong(), unicode);
    }

    @Override
    public RestAction<Void> clearReactions()
    {
        if (!isFromType(ChannelType.TEXT))
            throw new IllegalStateException("Cannot clear reactions from a message in a Group or PrivateChannel.");
        return getTextChannel().clearReactionsById(getId());
    }

    @Override
    public MessageType getType()
    {
        return type;
    }

    @Override
    public long getIdLong()
    {
        return id;
    }

    @Override
    public synchronized List<User> getMentionedUsers()
    {
        if (userMentions == null)
        {
            userMentions = new ArrayList<>();
            Matcher matcher = MentionType.USER.getPattern().matcher(content);
            while (matcher.find())
            {
                String id = matcher.group(1);
                User user = api.getUserById(id);
                if (user == null)
                {
                    try
                    {
                        user = api.getFakeUserMap().get(MiscUtil.parseSnowflake(id));
                    } catch (NumberFormatException ignored) {}
                }

                if (user != null)
                    userMentions.add(user);
            }
            userMentions = Collections.unmodifiableList(userMentions);
        }

        return userMentions;
    }

    @Override
    public synchronized List<TextChannel> getMentionedChannels()
    {
        if (channelMentions == null)
        {
            channelMentions = new ArrayList<>();
            Matcher matcher = MentionType.CHANNEL.getPattern().matcher(content);
            while (matcher.find())
            {
                String id = matcher.group(1);
                TextChannel channel = api.getTextChannelById(id);
                if (channel != null)
                    channelMentions.add(channel);
            }
            channelMentions = Collections.unmodifiableList(channelMentions);
        }

        return channelMentions;
    }

    @Override
    public synchronized List<Role> getMentionedRoles()
    {
        if (roleMentions == null)
        {
            roleMentions = new ArrayList<>();
            Matcher matcher = MentionType.ROLE.getPattern().matcher(content);
            while (matcher.find())
            {
                String id = matcher.group(1);
                Role role = null;
                if (isFromType(ChannelType.TEXT)) // role lookup is faster if its in the same guild (no global map)
                    role = getGuild().getRoleById(id);
                if (role == null)
                    role = api.getRoleById(id);
                if (role != null)
                    roleMentions.add(role);
            }
            roleMentions = Collections.unmodifiableList(roleMentions);
        }

        return roleMentions;
    }

    @Override
    public List<Member> getMentionedMembers(Guild guild)
    {
        Args.notNull(guild, "Guild");
        List<User> mentionedUsers = getMentionedUsers();
        List<Member> members = new ArrayList<>();
        for (User user : mentionedUsers)
        {
            Member member = guild.getMember(user);
            if (member != null)
                members.add(member);
        }

        return Collections.unmodifiableList(members);
    }

    @Override
    public List<Member> getMentionedMembers()
    {
        if (isFromType(ChannelType.TEXT))
            return getMentionedMembers(getGuild());
        else
            throw new IllegalStateException("You must specify a Guild for Messages which are not sent from a TextChannel!");
    }

    @Override
    public List<IMentionable> getMentions(MentionType... types)
    {
        if (types == null || types.length == 0)
            return getMentions(MentionType.values());
        List<IMentionable> mentions = new ArrayList<>();
        // boolean duplicate checks
        boolean channel = false;
        boolean role = false;
        boolean user = false;
        boolean emote = false;
        for (MentionType type : types)
        {
            switch (type)
            {
                case EVERYONE:
                case HERE:
                default: continue;
                case CHANNEL:
                    if (!channel)
                        mentions.addAll(getMentionedChannels());
                    channel = true;
                    break;
                case USER:
                    if (!user)
                        mentions.addAll(getMentionedUsers());
                    user = true;
                    break;
                case ROLE:
                    if (!role)
                        mentions.addAll(getMentionedRoles());
                    role = true;
                    break;
                case EMOTE:
                    if (!emote)
                        mentions.addAll(getEmotes());
                    emote = true;
            }
        }
        return Collections.unmodifiableList(mentions);
    }

    @Override
    public boolean isMentioned(IMentionable user, MentionType... types)
    {
        return mentionsEveryone() || getMentions(types).contains(user);
    }

    @Override
    public boolean mentionsEveryone()
    {
        return mentionsEveryone;
    }

    @Override
    public boolean mentionsType(MentionType... types)
    {
        if (types == null || types.length == 0)
            return false;
        if (types.length == 1 && types[0] == MentionType.EVERYONE)
            return mentionsEveryone;
        for (MentionType type : types)
        {
            Matcher m = type.getPattern().matcher(content);
            if (!m.find())
                return false;
        }

        return true;
    }

    @Override
    public boolean isEdited()
    {
        return editedTime != null;
    }

    @Override
    public OffsetDateTime getEditedTime()
    {
        return editedTime;
    }

    @Override
    public User getAuthor()
    {
        return author;
    }

    @Override
    public Member getMember()
    {
        return isFromType(ChannelType.TEXT) ? getGuild().getMember(getAuthor()) : null;
    }

    @Override
    public String getContentStripped()
    {
        if (strippedContent != null)
            return strippedContent;
        synchronized (mutex)
        {
            if (strippedContent != null)
                return strippedContent;
            String tmp = getContentDisplay();
            //all the formatting keys to keep track of
            String[] keys = new String[]{ "*", "_", "`", "~~" };

            //find all tokens (formatting strings described above)
            TreeSet<FormatToken> tokens = new TreeSet<>(Comparator.comparingInt(t -> t.start));
            for (String key : keys)
            {
                Matcher matcher = Pattern.compile(Pattern.quote(key)).matcher(tmp);
                while (matcher.find())
                    tokens.add(new FormatToken(key, matcher.start()));
            }

            //iterate over all tokens, find all matching pairs, and add them to the list toRemove
            Deque<FormatToken> stack = new ArrayDeque<>();
            List<FormatToken> toRemove = new ArrayList<>();
            boolean inBlock = false;
            for (FormatToken token : tokens)
            {
                if (stack.isEmpty() || !stack.peek().format.equals(token.format) || stack.peek().start + token
                        .format.length() == token.start)

                {
                    //we are at opening tag
                    if (!inBlock)
                    {
                        //we are outside of block -> handle normally
                        if (token.format.equals("`"))
                        {
                            //block start... invalidate all previous tags
                            stack.clear();
                            inBlock = true;
                        }
                        stack.push(token);
                    }
                    else if (token.format.equals("`"))
                    {
                        //we are inside of a block -> handle only block tag
                        stack.push(token);
                    }
                }
                else if (!stack.isEmpty())
                {
                    //we found a matching close-tag
                    toRemove.add(stack.pop());
                    toRemove.add(token);
                    if (token.format.equals("`") && stack.isEmpty())
                        //close tag closed the block
                        inBlock = false;
                }
            }

            //sort tags to remove by their start-index and iteratively build the remaining string
            toRemove.sort(Comparator.comparingInt(t -> t.start));
            StringBuilder out = new StringBuilder();
            int currIndex = 0;
            for (FormatToken formatToken : toRemove)
            {
                if (currIndex < formatToken.start)
                    out.append(tmp.substring(currIndex, formatToken.start));
                currIndex = formatToken.start + formatToken.format.length();
            }
            if (currIndex < tmp.length())
                out.append(tmp.substring(currIndex));
            //return the stripped text, escape all remaining formatting characters (did not have matching
            // open/close before or were left/right of block
            return strippedContent = out.toString().replace("*", "\\*").replace("_", "\\_").replace("~", "\\~");
        }
    }

    @Override
    public String getContentDisplay()
    {
        if (altContent != null)
            return altContent;
        synchronized (mutex)
        {
            if (altContent != null)
                return altContent;
            String tmp = content;
            for (User user : getMentionedUsers())
            {
                if (isFromType(ChannelType.PRIVATE) || isFromType(ChannelType.GROUP))
                {
                    tmp = tmp.replace("<@" + user.getId() + '>', '@' + user.getName())
                            .replace("<@!" + user.getId() + '>', '@' + user.getName());
                }
                else
                {
                    String name;
                    if (getGuild().isMember(user))
                        name = getGuild().getMember(user).getEffectiveName();
                    else name = user.getName();
                    tmp = tmp.replace("<@" + user.getId() + '>', '@' + name)
                            .replace("<@!" + user.getId() + '>', '@' + name);
                }
            }
            for (Emote emote : getEmotes())
            {
                tmp = tmp.replace(emote.getAsMention(), ":" + emote.getName() + ":");
            }
            for (TextChannel mentionedChannel : getMentionedChannels())
            {
                tmp = tmp.replace("<#" + mentionedChannel.getId() + '>', '#' + mentionedChannel.getName());
            }
            for (Role mentionedRole : getMentionedRoles())
            {
                tmp = tmp.replace("<@&" + mentionedRole.getId() + '>', '@' + mentionedRole.getName());
            }
            return altContent = tmp;
        }
    }

    @Override
    public String getContentRaw()
    {
        return content;
    }

    @Override
    public List<String> getInvites()
    {
        if (invites != null)
            return invites;
        synchronized (mutex)
        {
            if (invites != null)
                return invites;
            invites = new ArrayList<>();
            Matcher m = INVITE_PATTERN.matcher(getContentRaw());
            while (m.find())
            {
                String code = m.group(1);
                invites.add(code);
            }
            return invites = Collections.unmodifiableList(invites);
        }
    }

    @Override
    public String getNonce()
    {
        return nonce;
    }

    @Override
    public boolean isFromType(ChannelType type)
    {
        return getChannelType() == type;
    }

    @Override
    public ChannelType getChannelType()
    {
        return channel.getType();
    }

    @Override
    public MessageChannel getChannel()
    {
        return channel;
    }

    @Override
    public PrivateChannel getPrivateChannel()
    {
        return isFromType(ChannelType.PRIVATE) ? (PrivateChannel) channel : null;
    }

    @Override
    public Group getGroup()
    {
        return isFromType(ChannelType.GROUP) ? (Group) channel : null;
    }

    @Override
    public TextChannel getTextChannel()
    {
        return isFromType(ChannelType.TEXT) ? (TextChannel) channel : null;
    }

    @Override
    public Guild getGuild()
    {
        return isFromType(ChannelType.TEXT) ? getTextChannel().getGuild() : null;
    }

    @Override
    public List<Attachment> getAttachments()
    {
        return attachments;
    }

    @Override
    public List<MessageEmbed> getEmbeds()
    {
        return embeds;
    }

    @Override
    public synchronized List<Emote> getEmotes()
    {
        if (this.emoteMentions == null)
        {
            emoteMentions = new ArrayList<>();
            Matcher matcher = MentionType.EMOTE.getPattern().matcher(getContentRaw());
            while (matcher.find())
            {
                final String emoteIdString = matcher.group(2);
                final long emoteId = Long.parseLong(emoteIdString);
                String emoteName = matcher.group(1);
                Emote emote = api.getEmoteById(emoteIdString);
                if (emote == null)
                    emote = new EmoteImpl(emoteId, api).setName(emoteName);
                emoteMentions.add(emote);
            }
            emoteMentions = Collections.unmodifiableList(emoteMentions);
        }
        return emoteMentions;
    }

    @Override
    public List<MessageReaction> getReactions()
    {
        return reactions;
    }

    @Override
    public boolean isWebhookMessage()
    {
        return fromWebhook;
    }

    @Override
    public boolean isTTS()
    {
        return isTTS;
    }

    @Override
    public RestAction<Message> editMessage(CharSequence newContent)
    {
        return editMessage(new MessageBuilder().append(newContent).build());
    }

    @Override
    public RestAction<Message> editMessage(MessageEmbed newContent)
    {
        return editMessage(new MessageBuilder().setEmbed(newContent).build());
    }

    @Override
    public RestAction<Message> editMessageFormat(String format, Object... args)
    {
        Args.notBlank(format, "Format String");
        return editMessage(new MessageBuilder().appendFormat(format, args).build());
    }

    @Override
    public RestAction<Message> editMessage(Message newContent)
    {
        if (!api.getSelfUser().equals(getAuthor()))
            throw new IllegalStateException("Attempted to update message that was not sent by this account. You cannot modify other User's messages!");

        return getChannel().editMessageById(getIdLong(), newContent);
    }

    @Override
    public RestAction<Void> delete()
    {
        if (!getJDA().getSelfUser().equals(getAuthor()))
        {
            if (isFromType(ChannelType.PRIVATE) || isFromType(ChannelType.GROUP))
                throw new IllegalStateException("Cannot delete another User's messages in a Group or PrivateChannel.");
            else if (!getGuild().getSelfMember()
                    .hasPermission((TextChannel) getChannel(), Permission.MESSAGE_MANAGE))
                throw new PermissionException(Permission.MESSAGE_MANAGE);
        }
        return channel.deleteMessageById(getIdLong());
    }

    public MessageImpl setMentionedUsers(List<User> mentions)
    {
        //We don't need to lazy load when we already receive the mentions in the json
        this.userMentions = mentions;
        return this;
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof MessageImpl))
            return false;
        MessageImpl oMsg = (MessageImpl) o;
        return this == oMsg || this.id == oMsg.id;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(id);
    }

    @Override
    public String toString()
    {
        return String.format("M:%#s:%.20s(%s)", author, this, getId());
    }

    public JSONObject toJSONObject()
    {
        JSONObject obj = new JSONObject();
        obj.put("content", content);
        obj.put("tts",     isTTS);
        if (!embeds.isEmpty())
            obj.put("embed", ((MessageEmbedImpl) embeds.get(0)).toJSONObject());
        return obj;
    }

    private void checkPermission(Permission permission)
    {
        if (channel.getType() == ChannelType.TEXT)
        {
            Channel location = (Channel) channel;
            if (!location.getGuild().getSelfMember().hasPermission(location, permission))
                throw new PermissionException(permission);
        }
    }

    private void checkFake(IFakeable o, String name)
    {
        if (o.isFake())
            throw new IllegalArgumentException("We are unable to use a fake " + name + " in this situation!");
    }

    @Override
    public void formatTo(Formatter formatter, int flags, int width, int precision)
    {
        boolean upper = (flags & FormattableFlags.UPPERCASE) == FormattableFlags.UPPERCASE;
        boolean leftJustified = (flags & FormattableFlags.LEFT_JUSTIFY) == FormattableFlags.LEFT_JUSTIFY;
        boolean alt = (flags & FormattableFlags.ALTERNATE) == FormattableFlags.ALTERNATE;

        String out = alt ? getContentRaw() : getContentDisplay();

        if (upper)
            out = out.toUpperCase(formatter.locale());

        try
        {
            Appendable appendable = formatter.out();
            if (precision > -1 && out.length() > precision)
            {
                appendable.append(StringUtils.truncate(out, precision - 3)).append("...");
                return;
            }

            if (leftJustified)
                appendable.append(StringUtils.rightPad(out, width));
            else
                appendable.append(StringUtils.leftPad(out, width));
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    private static class FormatToken {
        public final String format;
        public final int start;

        public FormatToken(String format, int start) {
            this.format = format;
            this.start = start;
        }
    }
}
