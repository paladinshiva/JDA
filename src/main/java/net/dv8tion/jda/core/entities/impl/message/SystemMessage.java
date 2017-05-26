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

package net.dv8tion.jda.core.entities.impl.message;

import net.dv8tion.jda.core.entities.*;

public abstract class SystemMessage implements ISnowflake
{
    protected final User author;
    protected final MessageChannel channel;
    protected final long messageId;
    protected final String content;

    public SystemMessage(User author, MessageChannel channel, long messageId, String content)
    {
        this.author = author;
        this.channel = channel;
        this.messageId = messageId;
        this.content = content;
    }

    public User getAuthor()
    {
        return author;
    }

    public ChannelType getChannelType()
    {
        return channel.getType();
    }

    public boolean isFromType(ChannelType channelType)
    {
        return getChannelType() == channelType;
    }

    public MessageChannel getChannel()
    {
        return channel;
    }

    public String getContent()
    {
        return content;
    }

    @Override
    public long getIdLong()
    {
        return messageId;
    }

    public abstract MessageType getType();
}
