package com.example.analytics;

import io.confluent.demo.Movie;
import io.confluent.demo.Rating;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.binder.kafka.streams.InteractiveQueryService;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@SpringBootApplication
@EnableBinding(AnalyticsBinding.class)
public class AnalyticsApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalyticsApplication.class, args);
	}
}


@RestController
class CountRestController {

	private final InteractiveQueryService registry;

	CountRestController(InteractiveQueryService registry) {
		this.registry = registry;
	}

	@GetMapping("/counts")
	Map<Long, RatedMovie> counts() {

		Map<Long, RatedMovie> counts = new HashMap<>();

		ReadOnlyKeyValueStore<Long, RatedMovie> queryableStoreType =
			this.registry.getQueryableStore(AnalyticsBinding.RATED_MOVIES, QueryableStoreTypes.keyValueStore());

		KeyValueIterator<Long, RatedMovie> all = queryableStoreType.all();
		while (all.hasNext()) {
			KeyValue<Long, RatedMovie> value = all.next();
			counts.put(value.key, value.value);
		}
		return counts;
	}
}

interface AnalyticsBinding {

	String RAW_RATINGS = "ratings";
	String AVERAGE_RATINGS = "avg-ratings";
	String AVERAGE_TABLE = "avg-table";
	String MOVIE_TABLE = "movies";
	String RATED_MOVIES = "rated-movies";

	@Input(RAW_RATINGS)
	KStream<Long, Rating> ratingsIn();

	@Output(AVERAGE_RATINGS)
	KStream<Long, Double> ratingsOut();

	@Input(MOVIE_TABLE)
	KTable<Long, Movie> moviesIn();

	@Input(AVERAGE_TABLE)
	KTable<Long, Double> avgRatings();

	@Output(RATED_MOVIES)
	KStream<Long, RatedMovie> moviesOut();
}

@Log4j2
@Component
class RatingAverager {

	@StreamListener
	@SendTo(AnalyticsBinding.AVERAGE_RATINGS)
	public KStream<Long, Double> averageRatings(
		@Input(AnalyticsBinding.RAW_RATINGS) KStream<Long, Rating> ratings) {
		KGroupedStream<Long, Double> ratingsById = ratings.mapValues(Rating::getRating).groupByKey();
		KTable<Long, Long> ratingCounts = ratingsById.count();
		KTable<Long, Double> ratingSums = ratingsById.reduce(Double::sum,
			Materialized.with(Serdes.Long(), Serdes.Double()));
		KTable<Long, Double> ratedMovies = ratingSums.join(ratingCounts,
			(sum, count) -> sum / count.doubleValue(),
			Materialized.with(Serdes.Long(), Serdes.Double()));
		return ratedMovies.toStream();
	}
}

@Component
class MovieProcessor {

	@StreamListener
	@SendTo(AnalyticsBinding.RATED_MOVIES)
	public KStream<Long, RatedMovie> joinMoviesAndRatings(
		@Input(AnalyticsBinding.MOVIE_TABLE) KTable<Long, Movie> movies,
		@Input(AnalyticsBinding.AVERAGE_TABLE) KTable<Long, Double> ratings) {

		ValueJoiner<Movie, Double, RatedMovie> joiner =
			(movie, rating) -> new RatedMovie(movie.getMovieId(),
				movie.getTitle(),
				movie.getReleaseYear(),
				rating);

		return movies.join(ratings, joiner).toStream();
	}
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class RatedMovie {
	private long movieId;
	private String title;
	private int releaseYear;
	private double rating;
}