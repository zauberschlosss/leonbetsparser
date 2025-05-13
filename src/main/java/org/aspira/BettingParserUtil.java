package org.aspira;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspira.sportsapi.dto.model.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
public class BettingParserUtil {
  private static final String BASE_URL = "https://leonbets.com";
  private static final String SPORTS_ENDPOINT = "/api-2/betline/sports?ctag=en-US&flags=urlv2";
  private static final String LEAGUE_DETAILS_ENDPOINT = "/api-2/betline/changes/all?ctag=en-US&vtag=9c2cd386-31e1-4ce9-a140-28e9b63a9300&league_id={{league_id}}&hideClosed=true&flags=reg,urlv2,mm2,rrc,nodup";

  private static final Collection<String> SPORTS_KEYWORDS = List.of("Football", "Tennis", "Ice Hockey", "Basketball");
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");
  private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(3);

  private final ObjectMapper mapper = getConfiguredObjectMapper();

  protected void process() {
    Collection<Sport> sports = getSelectedSports();
    List<AggregatedSportMatchesData> aggregatedSportMatchesData = fillSportsData(sports);

    aggregatedSportMatchesData.sort(
        Comparator.comparingInt(data -> SPORTS_KEYWORDS.stream()
            .toList()
            .indexOf(data.getSportsName()))
    );

    prettyPrintAggregatedData(aggregatedSportMatchesData);

    EXECUTOR_SERVICE.shutdown();
  }

  private List<AggregatedSportMatchesData> fillSportsData(Collection<Sport> sports) {
    List<AggregatedSportMatchesData> aggregatedSportMatchesData = new LinkedList<>();
    List<Future<?>> allSubmittedSportsTasks = new LinkedList<>();

    for (Sport sport : sports) {
      Runnable sportsRunnable = () -> {
        var sportMatchesData = new AggregatedSportMatchesData();
        sportMatchesData.setSportsName(sport.getName());

        Collection<League> topLeagues = sport.getRegions().stream()
            .map(Region::getLeagues)
            .flatMap(Collection::stream)
            .filter(League::getTop)
            .toList();

        var leagueDataAggregated = fillLeaguesData(topLeagues, sport.getName());

        sportMatchesData.setLeagues(leagueDataAggregated);
        aggregatedSportMatchesData.add(sportMatchesData);
        log.info("Total {} top Leagues: {}\n", sport.getName(), leagueDataAggregated.size());
      };

      Future<?> submittedSportsTask = EXECUTOR_SERVICE.submit(sportsRunnable);
      allSubmittedSportsTasks.add(submittedSportsTask);
    }

    allSubmittedSportsTasks.forEach(f -> {
      try {
        f.get();
      } catch (InterruptedException | ExecutionException e) {
        throw new RuntimeException("Error occurred while processing sports task", e);
      }
    });

    return aggregatedSportMatchesData;
  }

  private List<LeagueDataAggregated> fillLeaguesData(Collection<League> leagues, String sportName) {
    var leagueDataAggregated = new LinkedList<LeagueDataAggregated>();

    for (League league : leagues) {
      var leagueMatchesRequest = generateHttpRequest(generateLeagueDetailsUrl(league.getId()));
      LeagueData leagueData = getLeagueMatches(leagueMatchesRequest, sportName, league.getName());
      Collection<Match> leagueMatches = Collections.emptyList();

      if (!leagueData.getData().isEmpty()) {
        leagueMatches = leagueData.getData().stream()
            .limit(2)
            .toList();
      }

      LeagueDataAggregated leagueAggregated = new LeagueDataAggregated();
      leagueAggregated.setLeagueName(league.getName());

      var leagueMatchesAggregated = fillMatchesData(leagueMatches);

      leagueAggregated.setMatches(leagueMatchesAggregated);
      leagueDataAggregated.add(leagueAggregated);
    }

    return leagueDataAggregated;
  }

  private List<MatchesDataAggregated> fillMatchesData(Collection<Match> leagueMatches) {
    var leagueMatchesAggregated = new LinkedList<MatchesDataAggregated>();

    for (Match leagueMatch : leagueMatches) {
      var matchesData = new MatchesDataAggregated();
      matchesData.setMatchName(leagueMatch.getName());
      matchesData.setKickoffDateTime(convertEpochMilliToFormattedDate(leagueMatch.getKickoff()));
      matchesData.setMatchId(leagueMatch.getId());

      var marketDetailsAggregated = fillMarketDetails(leagueMatch.getMarkets());

      matchesData.setMarkets(marketDetailsAggregated);
      leagueMatchesAggregated.add(matchesData);
    }

    return leagueMatchesAggregated;
  }

  private static List<MarketDetailsAggregated> fillMarketDetails(List<Market> markets) {
    var marketDetailsAggregated = new LinkedList<MarketDetailsAggregated>();

    for (Market market : markets) {
      var marketDetails = new MarketDetailsAggregated();
      marketDetails.setName(market.getName());

      var runnerDetailsAggregated = fillRunnerDetails(market.getRunners());

      marketDetails.setRunners(runnerDetailsAggregated);
      marketDetailsAggregated.add(marketDetails);
    }

    return marketDetailsAggregated;
  }


  private static LinkedList<RunnerDataAggregated> fillRunnerDetails(List<Runner> runners) {
    var runnerDetailsAggregated = new LinkedList<RunnerDataAggregated>();

    for (Runner runner : runners) {
      var runnerData = new RunnerDataAggregated();
      runnerData.setName(runner.getName());
      runnerData.setRunnerId(runner.getId());
      runnerData.setPrice(runner.getPriceStr());

      runnerDetailsAggregated.add(runnerData);
    }

    return runnerDetailsAggregated;
  }

  private static void prettyPrintAggregatedData(Collection<AggregatedSportMatchesData> aggregatedSportMatchesData) {
    for (AggregatedSportMatchesData sport : aggregatedSportMatchesData) {
      for (LeagueDataAggregated league : sport.getLeagues()) {
        System.out.printf("%s, %s\n", sport.getSportsName(), league.getLeagueName());

        for (MatchesDataAggregated match : league.getMatches()) {
          System.out.printf("\t%s, %s, %d\n", match.getMatchName(), match.getKickoffDateTime(), match.getMatchId());

          for (MarketDetailsAggregated market : match.getMarkets()) {
            System.out.printf("\t\t%s\n", market.getName());

            for (RunnerDataAggregated runner : market.getRunners()) {
              System.out.printf("\t\t\t%s, %s, %d\n", runner.getName(), runner.getPrice(), runner.getRunnerId());
            }
          }
        }
      }
    }
  }

  private LeagueData getLeagueMatches(HttpRequest sportsRequest, String sportName, String leagueName) {
    LeagueData leagueMatches;

    try {
      HttpResponse<String> response = HTTP_CLIENT.send(sportsRequest, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        leagueMatches = mapper.readValue(response.body(), LeagueData.class);
        log.info("Successfully retrieved {} league matches data, league: {}", sportName, leagueName);
      } else {
        throw new RuntimeException("Failed to fetch league matches data, HTTP response code: " + response.statusCode());
      }
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException("Failed to fetch league matches data: ", e);
    }

    return leagueMatches;
  }

  private Collection<Sport> getSelectedSports() {
    var sportsRequest = generateHttpRequest(SPORTS_ENDPOINT);
    Collection<Sport> selectedSports;

    try {
      HttpResponse<String> response = HTTP_CLIENT.send(sportsRequest, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        selectedSports = mapper.readValue(response.body(), new TypeReference<>() {
        });
        log.info("Successfully fetched {} sports.\n", selectedSports.size());
        selectedSports = selectedSports.stream()
            .filter(sport -> SPORTS_KEYWORDS.contains(sport.getName()))
            .toList();

        if (selectedSports.size() != SPORTS_KEYWORDS.size()) {
          throw new RuntimeException("Selected sports count does not match, check API response");
        }
      } else {
        throw new RuntimeException("Failed to fetch sports data, HTTP response code: " + response.statusCode());
      }
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException("Failed to fetch sports data: ", e);
    }

    return selectedSports;
  }

  private static String generateLeagueDetailsUrl(Long leagueId) {
    return LEAGUE_DETAILS_ENDPOINT.replace("{{league_id}}", String.valueOf(leagueId));
  }

  private static HttpRequest generateHttpRequest(String requestEndpoint) {
    return HttpRequest.newBuilder()
        .uri(URI.create(BASE_URL + requestEndpoint))
        .header("Accept", "application/json")
        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64)")
        .GET()
        .build();
  }

  private static String convertEpochMilliToFormattedDate(long epochMilli) {
    ZonedDateTime dateTime = Instant.ofEpochMilli(epochMilli)
        .atZone(ZoneId.of("UTC"));

    return DATE_TIME_FORMATTER.format(dateTime);
  }

  private static ObjectMapper getConfiguredObjectMapper() {
    var objectMapper = new ObjectMapper();
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    return objectMapper;
  }
}
