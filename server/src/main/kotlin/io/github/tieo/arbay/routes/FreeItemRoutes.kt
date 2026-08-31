package io.github.tieo.arbay.routes

import io.github.tieo.arbay.classifier.ClipImageModel
import io.github.tieo.arbay.classifier.EmbeddingModel
import io.github.tieo.arbay.classifier.FeedbackAction
import io.github.tieo.arbay.classifier.FreeItemFeedbackStore
import io.github.tieo.arbay.classifier.FreeItemProfileStore
import io.github.tieo.arbay.classifier.FreeItemScorer
import io.github.tieo.arbay.classifier.FreeItemMonitor
import io.github.tieo.arbay.crawler.SavedSearchMonitor
import io.github.tieo.arbay.classifier.FreeItemStore
import io.github.tieo.arbay.classifier.ModelArena
import io.github.tieo.arbay.classifier.ModelRegistry
import io.github.tieo.arbay.crawler.KleinanzeigenCrawler
import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.ErrorSnapshotStore
import io.github.tieo.arbay.crawler.FetchProgressEmitter
import io.github.tieo.arbay.crawler.classifyException
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.plugins.BadRequestException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

fun Route.freeItemRoutes(savedSearches: SavedSearchMonitor) {
    route("/api/free-items") {

        // ── Profile ────────────────────────────────────────────────────────────
        get("/profile") {
            val profile = FreeItemProfileStore.get()
            if (profile == null) call.respond(HttpStatusCode.NoContent)
            else call.respond(profile)
        }

        post("/profile") {
            val profile = call.receive<FreeItemProfile>()
            // Only location is required — free items are local pickup. Description is
            // optional: with it blank the model learns purely from swipe feedback
            // (cold-start browse-to-train), scoring everything neutral until then.
            if (profile.location.isNullOrBlank()) {
                throw BadRequestException("Profile location is required.")
            }
            FreeItemProfileStore.set(profile)
            // Start/stop background monitor based on tracking flag
            if (profile.trackingEnabled) FreeItemMonitor.start() else FreeItemMonitor.stop()
            call.respond(profile)
        }

        // ── Feedback ───────────────────────────────────────────────────────────
        post("/feedback") {
            val feedback = try {
                call.receive<ListingFeedback>()
            } catch (e: Exception) {
                throw BadRequestException("Invalid feedback body: ${e.message}")
            }
            val action = try {
                FeedbackAction.valueOf(feedback.action.name)
            } catch (e: IllegalArgumentException) {
                throw BadRequestException("Unknown action '${feedback.action}'. Must be LOVE, LIKE, DISLIKE, or PASS.")
            }
            // Build a minimal Listing from the metadata so the store can persist it
            val listing = if (feedback.url != null) {
                io.github.tieo.arbay.model.Listing(
                    id = feedback.listingId,
                    platformId = PlatformId.KLEINANZEIGEN,
                    externalId = feedback.listingId.removePrefix("KLEINANZEIGEN:"),
                    url = feedback.url ?: "",
                    title = feedback.title,
                    price = Money.cents(0),
                    imageUrls = listOfNotNull(feedback.imageUrl),
                    location = feedback.locationText?.let { Location.parse(it) },
                    description = feedback.description,
                    relevanceScore = feedback.relevanceScore,
                    scrapedAt = kotlinx.datetime.Clock.System.now(),
                )
            } else null
            FreeItemFeedbackStore.add(feedback.listingId, feedback.title, action, listing)
            // Record outcome in arena for model accuracy tracking
            ModelArena.recordOutcome(feedback.listingId, action.name)
            // Retrain models incrementally after each feedback
            ModelRegistry.retrainAll()
            // Rescore remaining items with the updated model
            val rescored = if (feedback.remainingIds.isNotEmpty()) {
                ModelRegistry.rescoreCached(feedback.remainingIds)
            } else emptyMap()
            call.respond(FeedbackResponse(ok = true, rescored = rescored))
        }

        // ── Undo feedback (remove love/dislike) ───────────────────────────────
        delete("/feedback/{listingId}") {
            val listingId = call.parameters["listingId"]
                ?: throw BadRequestException("Missing listingId")
            val removed = FreeItemFeedbackStore.removeFeedback(listingId)
            // Retrain to remove the undone feedback's influence
            if (removed) ModelRegistry.retrainAll()
            call.respond(mapOf("removed" to removed))
        }

        // ── Insights ──────────────────────────────────────────────────────────
        get("/insights") {
            call.respond(FreeItemInsights(
                embeddingAvailable = EmbeddingModel.isAvailable,
                seenIds = FreeItemFeedbackStore.seenIds(),
            ))
        }

        // ── History (all feedback items with full metadata) ───────────────────
        get("/history") {
            val action = call.queryParameters["action"] // optional: "LOVE" or "DISLIKE"
            val items = when (action?.uppercase()) {
                "LOVE" -> FreeItemFeedbackStore.lovedItems()
                "DISLIKE" -> FreeItemFeedbackStore.dislikedItems()
                else -> FreeItemFeedbackStore.allFeedback()
            }
            val history = items.map { f ->
                FeedbackHistoryItem(
                    listingId = f.listingId,
                    title = f.title,
                    action = io.github.tieo.arbay.model.FeedbackAction.valueOf(f.action),
                    url = f.url,
                    imageUrl = f.imageUrl,
                    locationText = f.locationText,
                    description = f.description,
                    relevanceScore = f.relevanceScore,
                    timestamp = f.timestamp,
                )
            }
            call.respond(history)
        }

        // ── Stats ─────────────────────────────────────────────────────────────
        get("/stats") {
            val s = FreeItemFeedbackStore.stats()
            call.respond(FreeItemStats(
                totalLoved = s.totalLoved,
                totalDisliked = s.totalDisliked,
                totalSeen = s.totalSeen,
                embeddingAvailable = EmbeddingModel.isAvailable,
            ))
        }

        // ── Model evaluation ──────────────────────────────────────────────────
        get("/evaluation") {
            val all = FreeItemFeedbackStore.allFeedback()
            val withScores = all.filter { it.relevanceScore != null }

            // Group by action
            val loved = withScores.filter { it.action == "LOVE" || it.action == "LIKE" }
            val disliked = withScores.filter { it.action == "DISLIKE" }
            val passed = withScores.filter { it.action == "PASS" }

            fun avg(list: List<io.github.tieo.arbay.classifier.StoredFeedback>) =
                if (list.isEmpty()) 0.0 else list.mapNotNull { it.relevanceScore }.average()
            fun median(list: List<io.github.tieo.arbay.classifier.StoredFeedback>): Double {
                val scores = list.mapNotNull { it.relevanceScore }.sorted()
                if (scores.isEmpty()) return 0.0
                return scores[scores.size / 2]
            }

            // Precision@threshold: of items scored >= threshold, how many were loved?
            val threshold = 0.6
            val aboveThreshold = withScores.filter { (it.relevanceScore ?: 0.0) >= threshold }
            val precision = if (aboveThreshold.isEmpty()) 0.0
                else aboveThreshold.count { it.action == "LOVE" || it.action == "LIKE" }.toDouble() / aboveThreshold.size

            // Recall: of loved items, how many had score >= threshold?
            val recall = if (loved.isEmpty()) 0.0
                else loved.count { (it.relevanceScore ?: 0.0) >= threshold }.toDouble() / loved.size

            // Separation: avg loved score - avg disliked score (higher = better separation)
            val separation = avg(loved) - avg(disliked)

            call.respond(mapOf(
                "totalWithScores" to "${withScores.size}",
                "lovedCount" to "${loved.size}",
                "dislikedCount" to "${disliked.size}",
                "passedCount" to "${passed.size}",
                "avgScoreLoved" to "%.3f".format(avg(loved)),
                "avgScoreDisliked" to "%.3f".format(avg(disliked)),
                "avgScorePassed" to "%.3f".format(avg(passed)),
                "medianScoreLoved" to "%.3f".format(median(loved)),
                "medianScoreDisliked" to "%.3f".format(median(disliked)),
                "medianScorePassed" to "%.3f".format(median(passed)),
                "precisionAt${threshold}" to "%.3f".format(precision),
                "recallAt${threshold}" to "%.3f".format(recall),
                "scoreSeparation" to "%.3f".format(separation),
                "embeddingAvailable" to EmbeddingModel.isAvailable.toString(),
                "verdict" to when {
                    withScores.size < 10 -> "Not enough data yet (need at least 10 scored items)"
                    separation < 0.02 -> "Model has NO separation — loves and dislikes score similarly. Needs better features."
                    separation < 0.05 -> "Model has WEAK separation. Some signal but noisy."
                    separation < 0.1 -> "Model has MODERATE separation. Working but room for improvement."
                    else -> "Model has GOOD separation (${separation.let { "%.3f".format(it) }}). Scores align with preferences."
                },
            ))
        }

        // ── Multi-model comparison ────────────────────────────────────────────
        get("/evaluation/compare") {
            val all = FreeItemFeedbackStore.allFeedback()
            val withEmbeddings = all.filter { it.embedding.isNotEmpty() }
            if (withEmbeddings.size < 10) {
                call.respond(mapOf("error" to "Need at least 10 items with embeddings for comparison"))
                return@get
            }

            val profileEmb = FreeItemProfileStore.getEmbedding()
            val lovedEmbs = withEmbeddings.filter { it.action == "LOVE" || it.action == "LIKE" }.map { it.embedding.toFloatArray() }
            val dislikedEmbs = withEmbeddings.filter { it.action == "DISLIKE" }.map { it.embedding.toFloatArray() }

            // Different weight configurations to compare
            data class ScoringConfig(val name: String, val profileW: Double, val loveW: Double, val dislikeW: Double, val keywordW: Double)
            val configs = listOf(
                ScoringConfig("current", 0.7, 0.3, 0.5, 0.15),
                ScoringConfig("profile-heavy", 0.9, 0.1, 0.3, 0.1),
                ScoringConfig("love-heavy", 0.4, 0.6, 0.3, 0.1),
                ScoringConfig("keyword-heavy", 0.5, 0.2, 0.3, 0.3),
                ScoringConfig("balanced", 0.5, 0.3, 0.4, 0.2),
            )

            val profileText = FreeItemProfileStore.get()?.description

            fun scoreWith(emb: FloatArray, cfg: ScoringConfig): Double {
                val profileSim = if (profileEmb != null) (EmbeddingModel.similarity(emb, profileEmb) + 1.0) / 2.0 else 0.5
                val loveSim = if (lovedEmbs.isNotEmpty()) lovedEmbs.maxOf { (EmbeddingModel.similarity(emb, it) + 1.0) / 2.0 } else null
                val dislikeSim = if (dislikedEmbs.isNotEmpty()) dislikedEmbs.maxOf { (EmbeddingModel.similarity(emb, it) + 1.0) / 2.0 } else null

                var score = profileSim * cfg.profileW
                if (loveSim != null) score += loveSim * cfg.loveW
                if (dislikeSim != null) score -= ((dislikeSim - profileSim).coerceAtLeast(0.0)) * cfg.dislikeW
                return score.coerceIn(0.0, 1.0)
            }

            val results = configs.map { cfg ->
                val scores = withEmbeddings.map { f ->
                    val emb = f.embedding.toFloatArray()
                    val score = scoreWith(emb, cfg)
                    Triple(f.action, score, f.listingId)
                }
                val loved = scores.filter { it.first == "LOVE" || it.first == "LIKE" }
                val disliked = scores.filter { it.first == "DISLIKE" }
                val avgLoved = if (loved.isEmpty()) 0.0 else loved.map { it.second }.average()
                val avgDisliked = if (disliked.isEmpty()) 0.0 else disliked.map { it.second }.average()
                val separation = avgLoved - avgDisliked
                val threshold = 0.5
                val aboveThreshold = scores.filter { it.second >= threshold }
                val precision = if (aboveThreshold.isEmpty()) 0.0 else aboveThreshold.count { it.first == "LOVE" || it.first == "LIKE" }.toDouble() / aboveThreshold.size
                val recall = if (loved.isEmpty()) 0.0 else loved.count { it.second >= threshold }.toDouble() / loved.size

                mapOf(
                    "config" to cfg.name,
                    "weights" to "profile=${cfg.profileW} love=${cfg.loveW} dislike=${cfg.dislikeW} keyword=${cfg.keywordW}",
                    "avgLoved" to "%.4f".format(avgLoved),
                    "avgDisliked" to "%.4f".format(avgDisliked),
                    "separation" to "%.4f".format(separation),
                    "precision@0.5" to "%.3f".format(precision),
                    "recall@0.5" to "%.3f".format(recall),
                )
            }
            call.respond(results)
        }

        // ── Tracking & new matches ────────────────────────────────────────────
        get("/tracking/status") {
            call.respond(mapOf(
                "enabled" to FreeItemMonitor.isRunning,
                "totalTracked" to FreeItemStore.totalCount(),
                "pendingMatches" to FreeItemMonitor.pendingMatchCount(),
            ))
        }

        get("/tracking/matches") {
            val matches = FreeItemMonitor.consumeMatches()
            call.respond(matches.map { item ->
                NewMatch(
                    listingId = item.listingId,
                    title = item.title,
                    url = item.url,
                    imageUrl = item.imageUrl,
                    locationText = item.locationText,
                    description = item.description,
                    relevanceScore = item.relevanceScore,
                    firstSeen = item.firstSeen,
                )
            })
        }

        // ── Streaming search ───────────────────────────────────────────────────
        get("/stream") {
            val searchText = call.queryParameters["q"] ?: ""
            val startPage = call.queryParameters["startPage"]?.toIntOrNull() ?: 1
            val batchSize = call.queryParameters["batchSize"]?.toIntOrNull()?.coerceIn(5, 100) ?: 25
            val profile = FreeItemProfileStore.get()
            if (profile?.location.isNullOrBlank()) {
                throw BadRequestException("Location must be set in profile before searching. Set a city in the Free Items profile.")
            }
            val searchQuery = SearchQuery(
                text = searchText,
                platforms = listOf(PlatformId.KLEINANZEIGEN),
                freeOnly = true,
                location = profile?.location,
                radiusKm = call.queryParameters["radiusKm"]?.toIntOrNull() ?: profile?.radiusKm ?: 30,
                maxPages = batchSize,
                startPage = startPage,
                category = MarketGroup.GENERAL,
            )

            val profileEmbedding = FreeItemProfileStore.getEmbedding()

            val crawler = CrawlerRegistry.crawlerFor(PlatformId.KLEINANZEIGEN)
                ?: throw BadRequestException("Kleinanzeigen crawler not available")

            call.respondTextWriter(contentType = ContentType.Text.Plain) {
                val startEvent = CrawlerSearchEvent(
                    type = CrawlerEventType.SEARCH_STARTED,
                    platform = "",
                    totalPlatforms = 1,
                )
                write(json.encodeToString(startEvent) + "\n")
                flush()

                // PLATFORM_STARTED
                write(json.encodeToString(CrawlerSearchEvent(
                    type = CrawlerEventType.PLATFORM_STARTED,
                    platform = PlatformId.KLEINANZEIGEN.name,
                    platformName = PlatformId.KLEINANZEIGEN.displayName,
                )) + "\n")
                flush()

                val progressEmitter = FetchProgressEmitter { stage ->
                    synchronized(this@respondTextWriter) {
                        write(json.encodeToString(CrawlerSearchEvent(
                            type = CrawlerEventType.PLATFORM_PROGRESS,
                            platform = PlatformId.KLEINANZEIGEN.name,
                            platformName = PlatformId.KLEINANZEIGEN.displayName,
                            fetchStage = stage,
                        )) + "\n")
                        flush()
                    }
                }

                val scoringContext = ModelRegistry.buildContext()
                val activeModelId = ModelRegistry.activeModelId
                val allScored = mutableListOf<Listing>()
                var rawTotal = 0
                var streamHasMore = false

                // Image affinity: nudge the text-model rank by how much a listing's photo
                // resembles photos the user loved/disliked (CLIP space). No-op until there
                // are reference photos, so cold-start ranking is unaffected.
                val lovedImages = FreeItemFeedbackStore.lovedImageEmbeddings()
                val dislikedImages = FreeItemFeedbackStore.dislikedImageEmbeddings()
                val imageWeight = 0.25

                fun scoreListings(listings: List<Listing>): List<Listing> =
                    listings.mapNotNull { listing ->
                        val text = buildString {
                            append(listing.title)
                            listing.description?.let { append(" $it") }
                        }
                        val embedding = EmbeddingModel.embed(text) ?: return@mapNotNull null
                        ModelRegistry.cacheEmbedding(listing.id, embedding, text)
                        val allScores = ModelRegistry.scoreAll(embedding, text, scoringContext)
                        val activeScore = allScores[activeModelId] ?: 0.0
                        ModelArena.recordPredictions(listing.id, allScores)
                        val score = if (lovedImages.isEmpty() && dislikedImages.isEmpty()) {
                            activeScore
                        } else {
                            val imgEmb = ClipImageModel.embedUrl(listing.imageUrls.firstOrNull { it.startsWith("http") })
                            (activeScore + imageWeight * ClipImageModel.affinity(imgEmb, lovedImages, dislikedImages)).coerceIn(0.0, 1.0)
                        }
                        listing.copy(relevanceScore = score, modelScores = allScores)
                    }.sortedByDescending { it.relevanceScore }

                try {
                    val kleinanzeigenCrawler = crawler as KleinanzeigenCrawler
                    val (total, hasMore) = withTimeout(300_000L) {
                        kotlinx.coroutines.withContext(progressEmitter) {
                            kleinanzeigenCrawler.searchFreeItemsStreaming(searchQuery) { page, pageResults, totalSoFar ->
                                rawTotal = totalSoFar
                                val scored = scoreListings(pageResults)
                                allScored.addAll(scored)

                                // Send incremental results per page
                                synchronized(this@respondTextWriter) {
                                    write(json.encodeToString(CrawlerSearchEvent(
                                        type = CrawlerEventType.PLATFORM_PROGRESS,
                                        platform = PlatformId.KLEINANZEIGEN.name,
                                        platformName = PlatformId.KLEINANZEIGEN.displayName,
                                        resultCount = allScored.size,
                                        rawCount = totalSoFar,
                                        listings = scored,
                                        fetchStage = "PAGE_$page",
                                    )) + "\n")
                                    flush()
                                }
                            }
                        }
                    }
                    streamHasMore = hasMore

                    // Track all scored items
                    FreeItemStore.trackBatch(allScored)

                    // Rank strictly by fit — best matches first. No exploration or model-disagreement
                    // reshuffling: injecting low-scored items into top slots reads as broken sorting.
                    val sorted = allScored.sortedByDescending { it.relevanceScore }

                    write(json.encodeToString(CrawlerSearchEvent(
                        type = CrawlerEventType.PLATFORM_DONE,
                        platform = PlatformId.KLEINANZEIGEN.name,
                        platformName = PlatformId.KLEINANZEIGEN.displayName,
                        resultCount = sorted.size,
                        rawCount = rawTotal,
                        listings = sorted,
                        completedPlatforms = 1,
                        totalPlatforms = 1,
                        hasMore = streamHasMore,
                        nextPage = startPage + batchSize,
                    )) + "\n")
                    flush()
                } catch (e: TimeoutCancellationException) {
                    write(json.encodeToString(CrawlerSearchEvent(
                        type = CrawlerEventType.PLATFORM_ERROR,
                        platform = PlatformId.KLEINANZEIGEN.name,
                        platformName = PlatformId.KLEINANZEIGEN.displayName,
                        error = "Timeout after 300s",
                        errorType = "TIMEOUT",
                        completedPlatforms = 1,
                        totalPlatforms = 1,
                    )) + "\n")
                    flush()
                } catch (e: CrawlerBlockedException) {
                    val snapId = try { ErrorSnapshotStore.capture(PlatformId.KLEINANZEIGEN.name, searchText, e, e.errorType) } catch (_: Exception) { "?" }
                    write(json.encodeToString(CrawlerSearchEvent(
                        type = CrawlerEventType.PLATFORM_ERROR,
                        platform = PlatformId.KLEINANZEIGEN.name,
                        platformName = PlatformId.KLEINANZEIGEN.displayName,
                        error = "${e.message} [$snapId]",
                        errorType = e.errorType.name,
                        completedPlatforms = 1,
                        totalPlatforms = 1,
                    )) + "\n")
                    flush()
                } catch (e: Exception) {
                    val errorType = classifyException(e)
                    val snapId = try { ErrorSnapshotStore.capture(PlatformId.KLEINANZEIGEN.name, searchText, e, errorType) } catch (_: Exception) { "?" }
                    write(json.encodeToString(CrawlerSearchEvent(
                        type = CrawlerEventType.PLATFORM_ERROR,
                        platform = PlatformId.KLEINANZEIGEN.name,
                        platformName = PlatformId.KLEINANZEIGEN.displayName,
                        error = "${e.message ?: "Unknown error"} [$snapId]",
                        errorType = errorType.name,
                        completedPlatforms = 1,
                        totalPlatforms = 1,
                    )) + "\n")
                    flush()
                }

                write(json.encodeToString(CrawlerSearchEvent(
                    type = CrawlerEventType.SEARCH_COMPLETE,
                    platform = "",
                    completedPlatforms = 1,
                    totalPlatforms = 1,
                )) + "\n")
                flush()
            }
        }
    }

    // ── Model Arena ────────────────────────────────────────────────────────────
    route("/api/models") {

        // List all models with their arena stats
        get {
            val models = ModelRegistry.allModels().map { model ->
                val stats = ModelArena.getStats(model.id)
                mapOf(
                    "id" to model.id,
                    "name" to model.name,
                    "trainable" to model.trainable.toString(),
                    "active" to (model.id == ModelRegistry.activeModelId).toString(),
                    "totalPredictions" to (stats?.totalPredictions ?: 0).toString(),
                    "accuracy" to "%.3f".format(stats?.accuracy ?: 0.0),
                    "precision" to "%.3f".format(stats?.precision ?: 0.0),
                    "recall" to "%.3f".format(stats?.recall ?: 0.0),
                    "separation" to "%.3f".format(stats?.separation ?: 0.0),
                    "falseNegatives" to (stats?.falseNegatives ?: 0).toString(),
                )
            }
            call.respond(models)
        }

        // Arena leaderboard
        get("/arena") {
            call.respond(ModelArena.leaderboard())
        }

        // Get a specific model's wrong predictions (debug)
        get("/{id}/predictions") {
            val modelId = call.parameters["id"] ?: throw BadRequestException("Missing model id")
            val stats = ModelArena.getStats(modelId)
                ?: throw BadRequestException("No stats for model '$modelId'")
            call.respond(mapOf(
                "modelId" to modelId,
                "accuracy" to "%.3f".format(stats.accuracy),
                "precision" to "%.3f".format(stats.precision),
                "recall" to "%.3f".format(stats.recall),
                "truePositives" to stats.truePositives,
                "trueNegatives" to stats.trueNegatives,
                "falsePositives" to stats.falsePositives,
                "falseNegatives" to stats.falseNegatives,
                "worstPredictions" to stats.worstPredictions,
            ))
        }

        // Set active model
        post("/active") {
            val body = call.receive<Map<String, String>>()
            val modelId = body["modelId"] ?: throw BadRequestException("Missing modelId")
            ModelRegistry.setActive(modelId)
            call.respond(mapOf("activeModelId" to modelId))
        }

        // Retrain all trainable models
        post("/retrain") {
            val results = ModelRegistry.retrainAll()
            call.respond(results.mapValues { (_, r) ->
                mapOf(
                    "examplesUsed" to r.examplesUsed.toString(),
                    "accuracy" to "%.3f".format(r.accuracy),
                    "details" to r.details.entries.joinToString(", ") { "${it.key}=${it.value}" },
                )
            })
        }
    }

    // ── Rejected items browser (false negative discovery) ──────────────────────
    route("/api/free-items") {
        get("/rejected") {
            // Return items from the store that were scored low by the active model
            // These are potential false negatives the user can browse
            val threshold = call.queryParameters["threshold"]?.toDoubleOrNull() ?: 0.4
            val limit = call.queryParameters["limit"]?.toIntOrNull()?.coerceIn(10, 200) ?: 50
            val items = FreeItemStore.allItems()
                .filter { (it.relevanceScore ?: 1.0) < threshold }
                .sortedByDescending { it.lastSeen }
                .take(limit)
                .map { RejectedItem(
                    listingId = it.listingId,
                    title = it.title,
                    url = it.url,
                    imageUrl = it.imageUrl,
                    locationText = it.locationText,
                    description = it.description,
                    relevanceScore = it.relevanceScore,
                    firstSeen = it.firstSeen,
                ) }
            call.respond(items)
        }

        // ── Notification Settings ────────────────────────────────────────────
        get("/notifications/settings") {
            call.respond(FreeItemMonitor.settings)
        }

        post("/notifications/settings") {
            val settings = call.receive<NotificationSettings>()
            FreeItemMonitor.updateSettings(settings)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }

        // What the device asks for on its schedule: the two things worth a notification, drained
        // so the same deal is not raised twice.
        get("/notifications/poll") {
            val result = FreeItemMonitor.pollNow()
            call.respond(result.copy(deals = savedSearches.drainDeals()))
        }

        // Get last poll result without triggering a new poll
        get("/notifications/last") {
            val result = FreeItemMonitor.getLastPollResult()
            if (result != null) call.respond(result) else call.respond(HttpStatusCode.NoContent)
        }
    }
}
