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

package net.dv8tion.jda.core.requests.restaction;

import net.dv8tion.jda.core.entities.impl.JDAImpl;
import net.dv8tion.jda.core.requests.Request;
import net.dv8tion.jda.core.requests.RestAction;
import org.apache.commons.collections4.map.CaseInsensitiveMap;

import java.util.concurrent.CompletableFuture;

public class RequestFuture<T> extends CompletableFuture<T>
{
    final Request<T> request;

    public RequestFuture(RestAction<T> restAction, boolean shouldQueue, CaseInsensitiveMap<String, String> headers)
    {
        this.request = new Request<T>(restAction, this::complete, this::completeExceptionally, shouldQueue, headers);
        ((JDAImpl) restAction.getJDA()).getRequester().request(request);
    }

    public RequestFuture(RestAction<T> restAction, boolean shouldQueue)
    {
        this(restAction, shouldQueue, null);
    }

    @Override
    public boolean cancel(boolean mayInterrupt)
    {
        request.cancel();
        return super.cancel(mayInterrupt);
    }
}
