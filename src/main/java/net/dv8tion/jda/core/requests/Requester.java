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

package net.dv8tion.jda.core.requests;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDAInfo;
import net.dv8tion.jda.core.entities.impl.JDAImpl;
import net.dv8tion.jda.core.requests.Route.CompiledRoute;
import net.dv8tion.jda.core.requests.ratelimit.BotRateLimiter;
import net.dv8tion.jda.core.requests.ratelimit.ClientRateLimiter;
import net.dv8tion.jda.core.utils.SimpleLog;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import java.util.LinkedHashSet;
import java.util.Set;

public class Requester
{
    public static final SimpleLog LOG = SimpleLog.getLog("JDARequester");
    public static final String DISCORD_API_PREFIX = "https://discordapp.com/api/";
    public static String USER_AGENT = "JDA DiscordBot (" + JDAInfo.GITHUB + ", " + JDAInfo.VERSION + ")";
    public static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final JDAImpl api;
    private final RateLimiter rateLimiter;

    private final OkHttpClient httpClient;

    public Requester(JDA api)
    {
        this(api, api.getAccountType());
    }

    public Requester(JDA api, AccountType accountType)
    {
        if (accountType == null)
            throw new NullPointerException("Provided accountType was null!");

        this.api = (JDAImpl) api;
        if (accountType == AccountType.BOT)
            rateLimiter = new BotRateLimiter(this, 5);
        else
            rateLimiter = new ClientRateLimiter(this, 5);
        
        httpClient = this.api.getHttpClientBuilder().build();
    }

    public JDAImpl getJDA()
    {
        return api;
    }

    public <T> void request(Request<T> apiRequest)
    {
        if (rateLimiter.isShutdown)
            throw new IllegalStateException("The Requester has been shutdown! No new requests can be requested!");
        if (apiRequest.shouldQueue())
        {
            rateLimiter.queueRequest(apiRequest);
        }
        else
        {
            Long retryAfter = execute(apiRequest);
            if (retryAfter != null)
                apiRequest.getRestAction().handleResponse(new Response(429, null, retryAfter), apiRequest);
        }
    }

    /**
     * Used to execute a Request. Processes request related to provided bucket.
     *
     * @param  apiRequest
     *         The API request that needs to be sent
     *
     * @return Non-null if the request was ratelimited. Returns a Long containing retry_after milliseconds until
     *         the request can be made again. This could either be for the Per-Route ratelimit or the Global ratelimit.
     *         <br>Check if globalCooldown is {@code null} to determine if it was Per-Route or Global.
     */
    public <T> Long execute(Request<T> apiRequest)
    {
        CompiledRoute route = apiRequest.getRoute();
        Long retryAfter = rateLimiter.getRateLimit(route);
        if (retryAfter != null)
            return retryAfter;

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder();

        String url = DISCORD_API_PREFIX + route.getCompiledRoute();
        builder.url(url);

        builder.method(apiRequest.getRoute().getMethod().toString(), apiRequest.getData());

        builder.header("user-agent", USER_AGENT);

        //adding token to all requests to the discord api or cdn pages
        //can't check for startsWith(DISCORD_API_PREFIX) due to cdn endpoints
//        if (api.getToken() != null && url.contains("discordapp.com"))
        {
            builder.header("authorization", api.getToken());
        }

        okhttp3.Request request = builder.build();

        Set<String> rays = new LinkedHashSet<>();
        try
        {
            okhttp3.Response response;
            int attempt = 0;
            do
            {
                //If the request has been canceled via the Future, don't execute.
                if (apiRequest.isCanceled())
                    return null;
                Call call = httpClient.newCall(request);
                response = call.execute();
                String cfRay = response.header("CF-RAY");
                if (cfRay != null)
                    rays.add(cfRay);

                if (response.code() < 500)
                    break;

                attempt++;
                LOG.debug(String.format("Requesting %s -> %s returned status %d... retrying (attempt %d)",
                        apiRequest.getRoute().getMethod().toString(),
                        url,
                        response.code(), attempt));
                try
                {
                    Thread.sleep(50 * attempt);
                }
                catch (InterruptedException ignored) {}
            }
            while (attempt < 3 && response.code() >= 500);

            if (response.code() >= 500)
            {
                //Epic failure from other end. Attempted 4 times.
                return null;
            }

            retryAfter = rateLimiter.handleResponse(route, response);
            if (!rays.isEmpty())
                LOG.debug("Received response with following cf-rays: " + rays);
            if (retryAfter == null)
                apiRequest.getRestAction().handleResponse(new Response(response, -1), apiRequest);

            return retryAfter;
        }
        catch (Exception e)
        {
            LOG.log(e); //This originally only printed on DEBUG in 2.x
            apiRequest.getRestAction().handleResponse(new Response(e), apiRequest);
            return null;
        }
    }

    public OkHttpClient getHttpClient()
    {
        return this.httpClient;
    }

    public RateLimiter getRateLimiter()
    {
        return rateLimiter;
    }

    public void shutdown()
    {
        rateLimiter.shutdown();
    }
}
