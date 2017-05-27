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

import net.dv8tion.jda.core.entities.EmbedType;
import net.dv8tion.jda.core.entities.MessageEmbed;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MessageEmbedImpl implements MessageEmbed
{
    private final Object mutex = new Object();

    private final String url;
    private final String title;
    private final String description;
    private final EmbedType type;
    private final OffsetDateTime timestamp;
    private final Color color;
    private final Thumbnail thumbnail;
    private final Provider siteProvider;
    private final AuthorInfo author;
    private final VideoInfo videoInfo;
    private final Footer footer;
    private final ImageInfo image;
    private final List<Field> fields;

    private volatile int length = -1;

    public MessageEmbedImpl(
        String url, String title, String description, EmbedType type, OffsetDateTime timestamp,
        Color color, Thumbnail thumbnail, Provider siteProvider, AuthorInfo author,
        VideoInfo videoInfo, Footer footer, ImageInfo image, List<Field> fields)
    {
        this.url = url;
        this.title = title;
        this.description = description;
        this.type = type;
        this.timestamp = timestamp;
        this.color = color;
        this.thumbnail = thumbnail;
        this.siteProvider = siteProvider;
        this.author = author;
        this.videoInfo = videoInfo;
        this.footer = footer;
        this.image = image;
        this.fields = fields != null && !fields.isEmpty()
                ? Collections.unmodifiableList(fields) : Collections.emptyList();
    }

    @Override
    public String getUrl()
    {
        return url;
    }

    @Override
    public String getTitle()
    {
        return title;
    }

    @Override
    public String getDescription()
    {
        return description;
    }

    @Override
    public EmbedType getType()
    {
        return type;
    }

    @Override
    public Thumbnail getThumbnail()
    {
        return thumbnail;
    }

    @Override
    public Provider getSiteProvider()
    {
        return siteProvider;
    }

    @Override
    public AuthorInfo getAuthor()
    {
        return author;
    }

    @Override
    public VideoInfo getVideoInfo()
    {
        return videoInfo;
    }
    
    @Override
    public Footer getFooter() {
        return footer;
    }

    @Override
    public ImageInfo getImage() {
        return image;
    }

    @Override
    public List<Field> getFields() {
        return fields;
    }
    
    @Override
    public Color getColor() {
        return color;
    }

    @Override
    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    @Override
    public int getLength()
    {
        if (length > -1)
            return length;
        synchronized (mutex)
        {
            if (length > -1)
                return length;
            length = 0;

            if (title != null)
                length += title.length();
            if (description != null)
                length += description.length();
            if (author != null)
                length += author.getName().length();
            if (footer != null)
                length += footer.getText().length();
            if (fields != null)
            {
                for (Field f : fields)
                    length += f.getName().length() + f.getValue().length();
            }

            return length;
        }
    }

    @Override
    public boolean equals(Object obj)
    {
        if (!(obj instanceof MessageEmbedImpl))
            return false;
        if (obj == this)
            return true;
        MessageEmbedImpl other = (MessageEmbedImpl) obj;
        return Objects.equals(url, other.url)
            && Objects.equals(title, other.title)
            && Objects.equals(description, other.description)
            && Objects.equals(type, other.type)
            && Objects.equals(thumbnail, other.thumbnail)
            && Objects.equals(siteProvider, other.siteProvider)
            && Objects.equals(author, other.author)
            && Objects.equals(videoInfo, other.videoInfo)
            && Objects.equals(footer, other.footer)
            && Objects.equals(image, other.image)
            && Objects.equals(color, other.color)
            && Objects.equals(timestamp, other.timestamp)
            && deepEquals(fields, other.fields);
    }

    private static <T> boolean deepEquals(Collection<T> first, Collection<T> second)
    {
        if (first != null)
        {
            if (second == null)
                return false;
            if (first.size() != second.size())
                return false;
            for (Iterator<T> itFirst = first.iterator(), itSecond = second.iterator(); itFirst.hasNext(); )
            {
                T elementFirst = itFirst.next();
                T elementSecond = itSecond.next();
                if (!Objects.equals(elementFirst, elementSecond))
                    return false;
            }
        }
        else if (second != null)
        {
            return false;
        }
        return true;
    }

    public JSONObject toJSONObject()
    {
        JSONObject obj = new JSONObject();
        if (url != null)
            obj.put("url", url);
        if (title != null)
            obj.put("title", title);
        if (description != null)
            obj.put("description", description);
        if (timestamp != null)
            obj.put("timestamp", timestamp.format(DateTimeFormatter.ISO_INSTANT));
        if (color != null)
            obj.put("color", color.getRGB() & 0xFFFFFF);
        if (thumbnail != null)
            obj.put("thumbnail", new JSONObject().put("url", thumbnail.getUrl()));
        if (siteProvider != null)
        {
            JSONObject siteProviderObj = new JSONObject();
            if (siteProvider.getName() != null)
                siteProviderObj.put("name", siteProvider.getName());
            if (siteProvider.getUrl() != null)
                siteProviderObj.put("url", siteProvider.getUrl());
            obj.put("provider", siteProviderObj);
        }
        if (author != null)
        {
            JSONObject authorObj = new JSONObject();
            if (author.getName() != null)
                authorObj.put("name", author.getName());
            if (author.getUrl() != null)
                authorObj.put("url", author.getUrl());
            if (author.getIconUrl() != null)
                authorObj.put("icon_url", author.getIconUrl());
            obj.put("author", authorObj);
        }
        if (videoInfo != null)
            obj.put("video", new JSONObject().put("url", videoInfo.getUrl()));
        if (footer != null)
        {
            JSONObject footerObj = new JSONObject();
            if (footer.getText() != null)
                footerObj.put("text", footer.getText());
            if (footer.getIconUrl() != null)
                footerObj.put("icon_url", footer.getIconUrl());
            obj.put("footer", footerObj);
        }
        if (image != null)
            obj.put("image", new JSONObject().put("url", image.getUrl()));
        if (!fields.isEmpty())
        {
            JSONArray fieldsArray = new JSONArray();
            for (Field field : fields)
            {
                fieldsArray
                    .put(new JSONObject()
                    .put("name", field.getName())
                    .put("value", field.getValue())
                    .put("inline", field.isInline()));
            }
            obj.put("fields", fieldsArray);
        }
        return obj;
    }
}
