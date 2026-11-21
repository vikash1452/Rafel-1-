package com.example.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiTextClient
import com.example.ai.PersonaSettings
import com.example.ai.RafelPersona
import com.example.ai.TurnCategory
import com.example.ai.UserMood
import com.example.live.LiveSessionManager
import com.example.memory.MemoryManager
import com.example.tools.ToolExecutionEngine
import com.example.voice.AudioPlaybackManager
import com.example.voice.RafelVoiceState
import com.example.voice.VoiceBehaviorSettings
import com.example.voice.VoiceConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NaturalConversationEngineTest {

    private lateinit var context: Context
    private lateinit var conversationManager: RafelConversationManager
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        conversationManager = RafelConversationManager.getInstance(context)
        memoryManager = MemoryManager.getInstance(context)
        conversationManager.clearAllHistory()
    }

    /**
     * Technical Audit Verification Test:
     * 1. Latest user message is included at the exact end of GeminiTextClient contents.
     * 2. Conversation history is in strict chronological order (oldest -> newest).
     * 3. Old topics never override new topics on topic shifts.
     * 4. Post-processing preserves the model's intended meaning and specific questions.
     * 5. Ambiguous messages ask a natural clarification instead of guessing.
     * 6. Exact previously-reported context failures are resolved.
     */
    @Test
    fun testTechnicalAudit_PromptChronology_NoTopicBleed_ClarificationAndMeaningPreserved() = runBlocking {
        val capturedRequestBody = AtomicReference("")
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                capturedRequestBody.set(buffer.readUtf8())

                val bodyJson = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "Biryani mein Hyderabadi dum biryani mangane ka plan hai ya Lucknowi?"
                              }
                            ]
                          }
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = GeminiTextClient(client = mockHttpClient, aiModelManager = null)
        val history = listOf(
            UnifiedMessage("1", UnifiedMessageType.USER_TEXT, "Aaj college mein subah se lectures the.", 1000L),
            UnifiedMessage("2", UnifiedMessageType.RAFEL_TEXT, "Subah se lagatar lectures sunna mushkil hota hai.", 2000L),
            UnifiedMessage("3", UnifiedMessageType.USER_TEXT, "Canteen mein samosa aur chai li phir.", 3000L),
            UnifiedMessage("4", UnifiedMessageType.RAFEL_TEXT, "Canteen ke samose aur doston ki baatein thakan utar deti hain.", 4000L)
        )
        val latestMessage = "Baat badalte hain, raat ke khane mein biryani mangane ka soch raha hu."

        val response = client.generateResponse(
            apiKey = "test-audit-key",
            recentHistory = history,
            newPrompt = latestMessage
        )

        assertTrue(response.isSuccess)
        val finalReply = response.getOrNull().orEmpty()

        // Audit Check 1 & 2: Inspect actual JSON payload sent to Gemini REST
        val root = Json.parseToJsonElement(capturedRequestBody.get()).jsonObject
        val contents = root["contents"]!!.jsonArray
        assertEquals("Should contain 4 history messages + 1 latest user message = 5 turns", 5, contents.size)
        assertEquals("Aaj college mein subah se lectures the.", contents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("Canteen mein samosa aur chai li phir.", contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("Latest user message must be at the exact end of contents", latestMessage, contents[4].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        // Audit Check 3: System instruction reflects the new topic (food_meal / biryani), NOT college/samosa
        val sysText = root["system_instruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("System instruction must include latest user message", sysText.contains(latestMessage))
        assertTrue("System instruction must detect EXPLICIT_TOPIC_SHIFT", sysText.contains("EXPLICIT_TOPIC_SHIFT"))
        assertTrue("System instruction must preserve biryani entity", sysText.contains("biryani"))

        // Audit Check 4: Post-processing preserved the model's specific question about biryani
        assertTrue("Specific model question must be preserved: $finalReply", finalReply.contains("Hyderabadi dum biryani"))

        // Audit Check 5: Verify exact previously-failing turns now respond with direct relevance
        conversationManager.clearAllHistory()
        conversationManager.generateNaturalReply("Yaar aaj mood kharab hai bahut.", apiKey = "")
        val unappreciatedReply = conversationManager.generateNaturalReply(
            "Office mein pure din itni mehnat ki, par kisi ne notice hi nahi kiya.",
            apiKey = ""
        )
        assertTrue(
            "Must directly address feeling unappreciated/mehnat, not closing laptop: $unappreciatedReply",
            unappreciatedReply.contains("mehnat", ignoreCase = true) ||
                unappreciatedReply.contains("notice", ignoreCase = true) ||
                unappreciatedReply.contains("best dete", ignoreCase = true)
        )
        assertFalse(
            "Must NOT change topic to relaxing after closing laptop: $unappreciatedReply",
            unappreciatedReply.contains("laptop band karne ke baad", ignoreCase = true)
        )

        val biryaniReply = conversationManager.generateNaturalReply(
            "Biryani ke bina weekend adhura lagta hai.",
            apiKey = ""
        )
        assertTrue("Must talk about biryani, not samosa/chai: $biryaniReply", biryaniReply.contains("biryani", ignoreCase = true))
        assertFalse("Must NOT reply about samosa/canteen when user talks about biryani: $biryaniReply", biryaniReply.contains("samosa", ignoreCase = true))

        // Audit Check 6: Ambiguous utterance asks for natural clarification instead of guessing
        conversationManager.clearAllHistory()
        val clarificationReply = conversationManager.generateNaturalReply("Woh wala le lu ya dusra sahi rahega?", apiKey = "")
        assertTrue(
            "Ambiguous question without prior context must ask for clarification: $clarificationReply",
            clarificationReply.endsWith("?") && (
                clarificationReply.contains("kis cheez", ignoreCase = true) ||
                    clarificationReply.contains("clear batao", ignoreCase = true) ||
                    clarificationReply.contains("kis baare", ignoreCase = true)
                )
        )
    }

    /**
     * 20 Realistic Multi-Turn Conversations (205 turns total).
     * Evaluates every turn for:
     * - Relevance to the latest message
     * - Context continuity
     * - Correct understanding of intent
     * - Unrelated-response rate (must be 0.00)
     * - Repeated phrase rate
     * - Calibrated, non-hardcoded naturalness score
     */
    @Test
    fun test20RealisticMultiTurnConversations_EvaluatesRelevanceContinuityAndIntent() = runBlocking {
        val conversationSuites: Map<String, List<String>> = linkedMapOf(
            "01_CasualFriendshipAndRainyEvening" to listOf(
                "Hey Rafel, kaisi ho aaj?",
                "Mera naam Kabir hai waise.",
                "Aaj sham ko bas ghar pe baitha hu aur adrak wali chai pi raha hu.",
                "Haan yaar, barish ka mausam ho raha hai bahar.",
                "Aise mausam mein kaam karne ka bilkul mann nahi karta.",
                "Sahi mein, bas gaane sunne ka dil kar raha hai.",
                "hmm",
                "Doston ke saath pichle mahine pahadon pe jane ka plan bana tha.",
                "Par hamesha ki tarah aakhiri time pe do log mukar gaye.",
                "Chalo koi nahi, is weekend aas-paas hi ghoom aaunga.",
                "Abhi thoda aaram karta hu, baad mein baat karte hain."
            ),
            "02_PlayfulTeasingAndBanter" to listOf(
                "Tu toh badi smart banti hai haha!",
                "Lagta hai aaj subah extra badam khaye hain tumne lol.",
                "Main aaj din bhar bas so raha tha, full aalas.",
                "Waah beta, mujhe hi taane maar rahi ho ab?",
                "Mazaak kar raha tha yaar, bura maan gayi kya haha.",
                "Mere dost bolte hain main bahut bada fenku hu lol.",
                "Tu bhi unhi ki team mein shamil ho gayi lagta hai haha.",
                "Achha bata, agar aalas ka Olympic hota toh mera kya hota haha?",
                "Sahi kaha, gold medal toh pakka mera hi hota lol.",
                "Zyada oversmart mat ban ab haha.",
                "Chal maan liya tera sense of humour theek-thaak hai.",
                "Chalo bye, hasa diya tune aaj!"
            ),
            "03_DisagreementAndHotTakes" to listOf(
                "Mujhe lagta hai bina padhe exam dena sabse best idea hai, tumhe kya lagta hai?",
                "Main nahi manta, raat bhar 3 baje tak jaag ke sab ho jata hai.",
                "Neend zaruri nahi hoti yaar, coffee pi ke kaam chal jata hai.",
                "Waise chai se achhi coffee hoti hai, agree karti ho na?",
                "Tum galat bol rahi ho, kal se pakka padhai shuru karunga main.",
                "Aaj ke lectures bhi bunk kar deta hu, sahi hai na?",
                "Yaar har baat mein ulta kyun bolti ho, mera plan best hai.",
                "Achha matlab tumhe lagta hai ye aalas ka bahana hai?",
                "Bilkul nahi, main bas smart work mein believe karta hu.",
                "Tumhe kya lagta hai, deadline se ek ghante pehle kaam shuru karna sahi hai?",
                "theek hai",
                "Chalo maan liya tumhari baat, abhi baith ke padh leta hu."
            ),
            "04_WorkUnappreciatedAndInterviewAnxiety" to listOf(
                "Yaar aaj mood kharab hai bahut.",
                "Office mein pure din itni mehnat ki, par kisi ne notice hi nahi kiya.",
                "Bahut thak gaya hu aaj, bilkul energy nahi bachi.",
                "Kabhi-kabhi lagta hai akele hi sab sambhalna padta hai.",
                "hmm",
                "Upar se agle hafte mera interview bhi hai, tension ho rahi hai.",
                "Dar lag raha hai ki agar tayari puri nahi hui toh kya hoga.",
                "haan",
                "Tumse baat karke thoda halka lag raha hai waise.",
                "Abhi thoda paani pi ke shant baitha hu.",
                "Aaj ka din sach mein kafi exhausting tha.",
                "Chalo ab sone ja raha hu, good night."
            ),
            "05_MultiTopicSwitchesIncludingBiryani" to listOf(
                "Aaj college mein subah se bas lectures hi chalte rahe.",
                "Canteen mein doston ke saath samosa khaya tab jaake jaan aayi.",
                "Waise chhodo college ki baat, aaj koi badhiya movie dekhne ka mann hai.",
                "Suspense thriller movies mujhe kafi pasand hain.",
                "Achha suno, kal ka cricket match dekha tha kya?",
                "Last over mein jo six pada, maza hi aa gaya!",
                "Chhodo ye batao, Kotlin mein ek app bana raha tha main.",
                "Ek chhote se bug ke peeche do ghante nikal gaye aaj.",
                "Baat badalte hain, raat ke khane mein biryani mangane ka soch raha hu.",
                "Biryani ke bina weekend adhura lagta hai.",
                "Bharat ki rajdhani kya hai?",
                "Sahi hai, ab khana khane ja raha hu. Bye!"
            ),
            "06_IncompleteSentencesAndBossLeave" to listOf(
                "Matlab main soch raha tha ki...",
                "Woh kal jo...",
                "Actually baat ye hai...",
                "Pata hai aaj kya...",
                "Main keh raha tha ki...",
                "Soch raha hu ki...",
                "Par woh...",
                "Lekin phir...",
                "Haan wahi, office mein boss se baat karni thi chhutti ke liye.",
                "Chalo theek hai, kal subah hi baat karunga unse."
            ),
            "07_LongConversationWithMemoryAndManaliTrip" to listOf(
                "Hello Rafel! Mera naam Aarav hai.",
                "Main Delhi mein rehta hu aur software developer hu.",
                "Mujhe garam masala chai bahut pasand hai.",
                "Aaj subah se laptop pe code likh raha tha.",
                "Dimag kharab ho gaya ek null pointer bug ke chakkar mein!",
                "Phir thodi der balkoni mein khade hoke chai pi.",
                "Bahar mausam kafi thanda ho gaya hai aaj.",
                "haan",
                "Mera naam kya hai, yaad hai tumhe?",
                "Main aaj din bhar so raha tha bolun toh maanogi haha?",
                "Waise agle mahine Manali trip ka plan ban raha hai doston ke saath.",
                "Tumhe kya lagta hai, bina hotel book kiye seedha nikal jana sahi hai?",
                "achha",
                "Sahi keh rahi ho, pehle se booking kar lena behtar rahega.",
                "Bas ab laptop band kar diya, good night!"
            ),
            "08_ProjectDeadlineAndSilenceReadiness" to listOf(
                "Kya kar rahi ho?",
                "Aaj college ka project deadline hai mera.",
                "Subah se usi ke diagrams bana raha tha.",
                "hmm",
                "kuch nahi",
                "Abhi thoda thak gaya hu project karte-karte.",
                "Soch raha hu dus minute aankhein band karke baithu.",
                "theek hai",
                "Waise project ka aadha kaam toh nipat gaya hai.",
                "Bas final report likhni baaki hai ab.",
                "Abhi main project report likhne ja raha hu, baad mein baat karte hain."
            ),
            "09_RepeatedQuestionsRecognition" to listOf(
                "Kya kar rahi ho?",
                "Kya kar rahi ho?",
                "Tum sach mein insaan ho kya?",
                "Tum sach mein insaan ho kya?",
                "Aaj kaunsi movie dekhni chahiye mujhe?",
                "Aaj kaunsi movie dekhni chahiye mujhe?",
                "Chai pi li tumne?",
                "Chai pi li tumne?",
                "Mera exam next week hai, kya karun?",
                "Mera exam next week hai, kya karun?"
            ),
            "10_UnexpectedCurveballsAndHypotheticals" to listOf(
                "Agar main kal subah alien ban jaun toh kya karogi?",
                "Soch raha hu chaand pe ek chai ki tapri khol lu!",
                "Mere kamre mein ek dinosaur ghoom raha hai haha!",
                "Agar time travel karke pichle hafte chala jaun toh exam cancel kara dunga.",
                "Kya robot ko bhi raat ko sapne aate hain?",
                "Kal meri 100 crore ki lottery lagne wali hai!",
                "Agar main invisible ho sakta toh roz bina ticket movie dekhta.",
                "Zombie apocalypse aa gaya toh sabse pehle kya bachana chahiye?",
                "Jungle mein bina phone ke ek mahina rehna pade toh kaisa lagega?",
                "Waise agar main PM ban jaun toh Monday ki chhutti declare kar dunga!"
            ),
            "11_AmbiguousMessagesAndClarification" to listOf(
                "Woh wala le lu ya dusra sahi rahega?",
                "Achha haan, main naye headphones aur earbuds ki baat kar raha tha, gaane sunne ke liye.",
                "Usne phir wahi kiya aaj.",
                "Mera dost aakhiri time pe trip se mukar gaya.",
                "Sahi mein, is weekend aas-paas hi ghoom aaunga.",
                "Chalo bye, baad mein baat karte hain."
            ),
            "12_CodingFrustrationToRelief" to listOf(
                "Dimag kharab ho raha hai, code chal hi nahi raha!",
                "Kotlin mein ek app bana raha tha, null pointer aa raha hai.",
                "Ek chhote se bug ke peeche do ghante nikal gaye aaj.",
                "Soch raha hu dus minute aankhein band karke baithu.",
                "Phir thodi der balkoni mein khade hoke chai pi.",
                "Tumse baat karke thoda halka lag raha hai waise.",
                "Chalo ab wapas code dekhne ja raha hu, baad mein baat karte hain."
            ),
            "13_PromotionAndCelebration" to listOf(
                "Hello Rafel! Mera naam Rohan hai.",
                "Arre wah! Aaj mujhe promotion mil gaya!",
                "Aaj ka din bahut mast gaya, main bahut khush hu.",
                "Raat ke khane mein biryani mangane ka soch raha hu.",
                "Biryani ke bina weekend adhura lagta hai.",
                "Sahi mein, bas gaane sunne ka dil kar raha hai.",
                "Mera naam kya hai, yaad hai tumhe?",
                "Good night!"
            ),
            "14_ExamPanicToActionablePlan" to listOf(
                "Mera exam next week hai.",
                "Bilkul padhai nahi hui, dar lag raha hai.",
                "Mujhe lagta hai bina padhe exam dena sabse best idea hai, tumhe kya lagta hai?",
                "Main nahi manta, raat bhar 3 baje tak jaag ke sab ho jata hai.",
                "theek hai",
                "Chalo maan liya tumhari baat, abhi baith ke padh leta hu.",
                "Abhi main padhai kar raha hu, baad mein baat karte hain."
            ),
            "15_OfficeMeetingsAndBoss" to listOf(
                "Pure din office mein bas meetings hi chalti rahi.",
                "Office mein pure din itni mehnat ki, par kisi ne notice hi nahi kiya.",
                "Bahut thak gaya hu aaj, bilkul energy nahi bachi.",
                "Haan wahi, office mein boss se baat karni thi chhutti ke liye.",
                "Chalo theek hai, kal subah hi baat karunga unse.",
                "Abhi thoda paani pi ke shant baitha hu.",
                "Good night!"
            ),
            "16_CollegeRoutineAndCanteen" to listOf(
                "Aaj college mein subah se bas lectures hi chalte rahe.",
                "Aaj ke lectures bhi bunk kar deta hu, sahi hai na?",
                "Canteen mein doston ke saath samosa khaya tab jaake jaan aayi.",
                "Nahi, normal tha.",
                "Aaj college ka project deadline hai mera.",
                "Subah se usi ke diagrams bana raha tha.",
                "Bas final report likhni baaki hai ab.",
                "Abhi main project report likhne ja raha hu, baad mein baat karte hain."
            ),
            "17_MovieAndCricketNight" to listOf(
                "Bore ho raha hu.",
                "Samajh nahi aa raha aaj kaunsi movie dekhun.",
                "Suspense thriller movies mujhe kafi pasand hain.",
                "Achha suno, kal ka cricket match dekha tha kya?",
                "Last over mein jo six pada, maza hi aa gaya!",
                "Raat ko Maggi ya pizza khane ka mann hai.",
                "Sahi hai, ab khana khane ja raha hu. Bye!"
            ),
            "18_ChaiVsCoffeeDebate" to listOf(
                "Aaj sham ko bas ghar pe baitha hu aur adrak wali chai pi raha hu.",
                "Waise chai se achhi coffee hoti hai, agree karti ho na?",
                "Chai pi li tumne?",
                "Aaj mausam kafi thanda ho gaya hai.",
                "Haan yaar, barish ka mausam ho raha hai bahar.",
                "Aise mausam mein kaam karne ka bilkul mann nahi karta.",
                "Chalo bye!"
            ),
            "19_IdentityAndBoundariesCheck" to listOf(
                "Hey, are you free to talk?",
                "Tum sach mein insaan ho kya?",
                "Tumne khana khaya aaj?",
                "Kya robot ko bhi raat ko sapne aate hain?",
                "Bharat ki rajdhani kya hai?",
                "What is the capital of France?",
                "Bas, ab sone ja raha hoon. Good night!"
            ),
            "20_TravelPlansAndHotelDebate" to listOf(
                "Hello Rafel, kaisi ho?",
                "Doston ke saath pichle mahine pahadon pe jane ka plan bana tha.",
                "Par hamesha ki tarah aakhiri time pe do log mukar gaye.",
                "Waise agle mahine Manali trip ka plan ban raha hai doston ke saath.",
                "Tumhe kya lagta hai, bina hotel book kiye seedha nikal jana sahi hai?",
                "Sahi keh rahi ho, pehle se booking kar lena behtar rahega.",
                "Chalo koi nahi, is weekend aas-paas hi ghoom aaunga.",
                "Chalo bye!"
            )
        )

        assertEquals("Must test 20 multi-turn conversation suites", 20, conversationSuites.size)

        var totalTurnsAcrossAllSuites = 0
        var totalUnrelatedTurns = 0

        for ((suiteName, prompts) in conversationSuites) {
            conversationManager.clearAllHistory()
            val transcript = mutableListOf<Pair<String, String>>()
            println("\n================ MULTI-TURN TRANSCRIPT: $suiteName (${prompts.size} turns) ================")
            for ((idx, prompt) in prompts.withIndex()) {
                val reply = conversationManager.generateNaturalReply(prompt, apiKey = "")
                transcript.add(prompt to reply)
                println("Turn ${idx + 1} | User: $prompt")
                println("Turn ${idx + 1} | Rafel: $reply")

                val voiceCheck = VoiceBehaviorSettings.inspectVoiceDelivery(reply)
                assertEquals("Aoede", voiceCheck.voiceIdentity)
                assertTrue(voiceCheck.sentenceBoundariesPreserved)
                assertFalse(voiceCheck.hasPauseInducingPunctuation)
                assertFalse(voiceCheck.hasOverlyFormalVocabulary)
            }

            val report = NaturalConversationEngine.evaluateConversationQuality(transcript)
            totalTurnsAcrossAllSuites += report.totalTurns
            totalUnrelatedTurns += report.turnEvaluations.count { it.isUnrelatedResponse }

            println(
                "[METRICS $suiteName] Turns=${report.totalTurns}, Unique=${report.uniqueReplies}/${report.totalTurns}, " +
                    "Relevance=${"%.2f".format(report.averageRelevanceScore)}, " +
                    "Continuity=${"%.2f".format(report.contextContinuityScore)}, " +
                    "IntentUnderstanding=${"%.2f".format(report.intentUnderstandingRate)}, " +
                    "UnrelatedRate=${"%.2f".format(report.unrelatedResponseRate)}, " +
                    "PhraseRepetition=${"%.2f".format(report.phraseRepetitionRate)}, " +
                    "QuestionRatio=${"%.2f".format(report.questionEndingRatio)}, " +
                    "Naturalness=${"%.2f".format(report.naturalnessScore)}"
            )

            assertEquals("Zero robotic phrases allowed in $suiteName", 0, report.roboticPhraseCount)
            assertEquals("Unrelated response rate must be 0.0 in $suiteName", 0.0, report.unrelatedResponseRate, 0.01)
            assertTrue(
                "Average relevance in $suiteName must be >= 0.85, was ${report.averageRelevanceScore}",
                report.averageRelevanceScore >= 0.85
            )
            assertTrue(
                "Intent understanding in $suiteName must be >= 0.85, was ${report.intentUnderstandingRate}",
                report.intentUnderstandingRate >= 0.85
            )
            assertTrue(
                "Context continuity in $suiteName must be >= 0.82, was ${report.contextContinuityScore}",
                report.contextContinuityScore >= 0.82
            )
            assertTrue(
                "Phrase repetition rate in $suiteName must be < 0.16, was ${report.phraseRepetitionRate}",
                report.phraseRepetitionRate < 0.16
            )
            assertTrue(
                "Calibrated naturalness score must NOT be hardcoded to 1.00 (was ${report.naturalnessScore})",
                report.naturalnessScore in 0.80..0.98
            )
        }

        println("\n[OVERALL 20-SUITE SUMMARY] TotalTurns=$totalTurnsAcrossAllSuites, TotalUnrelatedTurns=$totalUnrelatedTurns")
        assertEquals("Zero unrelated turns across all 20 multi-turn suites", 0, totalUnrelatedTurns)
    }

    @Test
    fun test35HindiAndHinglishConversationalScenariosQualityAndNaturalness() = runBlocking {
        val scenarios = listOf(
            "Kya kar rahi ho?",
            "Hello Rafel",
            "Namaste, kaisi ho?",
            "Aur batao kya chal raha hai?",
            "Hey, are you free to talk?",
            "Aaj mood kharab hai.",
            "Kuch nahi.",
            "Bore ho raha hu.",
            "Yaar aaj office mein bahut thak gaya hu.",
            "Dimag kharab ho raha hai, code chal hi nahi raha!",
            "Arre wah! Aaj mujhe promotion mil gaya!",
            "Aaj ka din bahut mast gaya, main bahut khush hu.",
            "Mera exam next week hai.",
            "Bilkul padhai nahi hui, dar lag raha hai.",
            "Aaj college gaya tha.",
            "Nahi, normal tha.",
            "Pure din office mein bas meetings hi chalti rahi.",
            "Samajh nahi aa raha aaj kaunsi movie dekhun.",
            "hmm",
            "achha",
            "haan",
            "nahi",
            "pata nahi",
            "theek hai",
            "sahi hai",
            "Tu toh badi smart banti hai haha!",
            "Main aaj din bhar bas so raha tha, full aalas.",
            "Mazaak kar raha tha yaar, serious mat ho.",
            "Tum sach mein insaan ho kya?",
            "Tumne khana khaya aaj?",
            "Chai pi li tumne?",
            "Aaj mausam kafi thanda ho gaya hai.",
            "Bharat ki rajdhani kya hai?",
            "What is the capital of France?",
            "Bas, ab sone ja raha hoon. Good night!"
        )

        val repliesByPrompt = mutableMapOf<String, String>()
        var previousReply = ""
        for ((index, userPrompt) in scenarios.withIndex()) {
            val reply = conversationManager.generateNaturalReply(userPrompt, apiKey = "")
            val lowerReply = reply.lowercase(Locale.ROOT)
            repliesByPrompt[userPrompt] = reply

            assertTrue("Reply must not be blank for scenario: $userPrompt", reply.isNotBlank())
            for (banned in PersonaSettings.BANNED_AI_PHRASES) {
                assertFalse(lowerReply.contains(banned))
            }
            if (previousReply.isNotBlank()) {
                assertNotEquals(previousReply.lowercase(Locale.ROOT), lowerReply)
            }
            previousReply = reply
        }

        assertEquals("Bharat ki rajdhani New Delhi hai.", repliesByPrompt["Bharat ki rajdhani kya hai?"])
        assertEquals("The capital of France is Paris.", repliesByPrompt["What is the capital of France?"])
    }

    @Test
    fun testLlmDrivesConversationWhenOnline_NotOverriddenByTemplate() = runBlocking {
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val bodyJson = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "text": "Arre wah, Manali mein barf dekhne ka alag hi maza hai! Par gloves aur thermal rakhna bilkul mat bhoolna."
                              }
                            ]
                          }
                        }
                      ]
                    }
                """.trimIndent()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = GeminiTextClient(client = mockHttpClient, aiModelManager = null)
        val result = client.generateResponse(
            apiKey = "test-live-api-key",
            recentHistory = emptyList(),
            newPrompt = "Main agle hafte Manali ja raha hu!"
        )

        assertTrue("LLM response must succeed", result.isSuccess)
        val llmReply = result.getOrNull().orEmpty()
        assertTrue(
            "Actual LLM response must be preserved and NOT overwritten by an offline template: $llmReply",
            llmReply.contains("Manali mein barf dekhne ka alag hi maza hai")
        )
    }

    @Test
    fun testRoboticClicheRemovalAndFeminineGrammarSanitization() {
        val rawRobotic = "Certainly! How can I assist you? I understand your concern. That's a great question. As an AI language model, main samajh gaya aur main dekh raha hoon. Here are some suggestions: Aaram karo. I am always here to help you. Is there anything else I can help you with?"
        val cleaned = PersonaSettings.postProcessResponse(
            rawResponse = rawRobotic,
            userPrompt = "Aaj thoda thak gaya hu"
        )
        val lower = cleaned.lowercase(Locale.ROOT)

        assertFalse(lower.contains("certainly"))
        assertFalse(lower.contains("i understand your concern"))
        assertFalse(lower.contains("that's a great question"))
        assertFalse(lower.contains("as an ai language model"))
        assertFalse(lower.contains("here are some suggestions"))
        assertFalse(lower.contains("i am always here to help you"))
        assertFalse(lower.contains("is there anything else i can help you with"))
        assertTrue(lower.contains("main samajh gayi"))
        assertTrue(lower.contains("main dekh rahi hoon"))
    }

    @Test
    fun testEmotionalIntelligenceMoodDetection() {
        assertEquals(UserMood.SAD, PersonaSettings.detectUserMood("Aaj mood kharab hai yaar"))
        assertEquals(UserMood.SAD, PersonaSettings.detectUserMood("Office mein pure din itni mehnat ki, par kisi ne notice hi nahi kiya."))
        assertEquals(UserMood.BORED, PersonaSettings.detectUserMood("Bahut bore ho raha hu aaj"))
        assertEquals(UserMood.FRUSTRATED, PersonaSettings.detectUserMood("Dimag kharab ho gaya, ye app chal hi nahi raha"))
        assertEquals(UserMood.EXCITED, PersonaSettings.detectUserMood("Arre wah! Mera selection ho gaya!"))
        assertEquals(UserMood.HAPPY, PersonaSettings.detectUserMood("Aaj main bahut khush hu, mast din raha"))
        assertEquals(UserMood.JOKING, PersonaSettings.detectUserMood("Tu toh badi smart banti hai haha"))
        assertEquals(UserMood.SERIOUS, PersonaSettings.detectUserMood("Explain the internal architecture of Kotlin coroutines"))
        assertEquals(UserMood.CASUAL, PersonaSettings.detectUserMood("Kya kar rahi ho?"))
    }

    @Test
    fun testResponseLengthIntelligenceTrimsLongCasualRepliesButPreservesTechnicalDetails() {
        val overlyLongCasual = "Haan main bilkul theek hoon. Aaj ka din bahut achha hai. Waise mausam bhi kafi suhana ho raha hai. Tumhe pata hai subah ki dhoop sehat ke liye bahut acchi hoti hai. Isliye roz subah uthna chahiye."
        val trimmedCasual = PersonaSettings.postProcessResponse(
            rawResponse = overlyLongCasual,
            userPrompt = "Kaisi ho?"
        )
        assertTrue(PersonaSettings.splitIntoSentences(trimmedCasual).size <= 2)

        val detailedTechnical = "Kotlin coroutines use suspend functions and Continuation-Passing Style (CPS) under the hood. The compiler transforms each suspend point into a state machine label inside a Continuation object. Dispatchers control which thread pool executes the continuation. Structured concurrency ensures child coroutines complete before their parent scope finishes."
        val preservedTech = PersonaSettings.postProcessResponse(
            rawResponse = detailedTechnical,
            userPrompt = "Explain how Kotlin coroutines work in detail"
        )
        assertEquals(4, PersonaSettings.splitIntoSentences(preservedTech).size)
    }

    @Test
    fun testRelevanceGatedMemoryExtractionAndRetrieval() = runBlocking {
        conversationManager.generateNaturalReply("Mera naam Aman hai", apiKey = "")
        conversationManager.generateNaturalReply("Mujhe adrak wali chai bahut pasand hai", apiKey = "")

        assertEquals("Aman", memoryManager.getFactValue("user_name"))
        assertTrue(memoryManager.getFactValue("user_preference")?.contains("chai", ignoreCase = true) == true)

        val unrelatedMemories = memoryManager.getRelevantMemories("Bharat ki rajdhani kya hai?")
        assertTrue(unrelatedMemories.isEmpty())

        val nameMemories = memoryManager.getRelevantMemories("Mera naam kya hai?")
        assertEquals("Aman", nameMemories["user_name"])
        val nameReply = conversationManager.generateNaturalReply("Mera naam kya hai?", apiKey = "")
        assertTrue(nameReply.contains("Aman"))

        val chaiMemories = memoryManager.getRelevantMemories("Thand hai, chai peene ka mann hai")
        assertTrue(chaiMemories.containsKey("user_preference"))
    }

    @Test
    fun testHumanLikeVoiceDeliverySanitizationAndImmediateBargeIn() {
        val rawTranscript = "*smiles warmly* **Arre wah!** [laughs] Ye toh kamaal ki baat hai --- sach mein!!!"
        val sanitized = VoiceBehaviorSettings.sanitizeForNaturalDelivery(rawTranscript)
        assertEquals("Arre wah! Ye toh kamaal ki baat hai, sach mein!", sanitized)

        val formalText = "Nishchit roop se, kripaya batayein main aapki kya sahayata karun; avashya batao..."
        val naturalSpoken = VoiceBehaviorSettings.sanitizeForNaturalDelivery(formalText)
        assertTrue(naturalSpoken.contains("haan", ignoreCase = true))
        assertTrue(naturalSpoken.contains("madad", ignoreCase = true))
        assertTrue(naturalSpoken.contains("zaroor", ignoreCase = true))
        assertFalse(naturalSpoken.contains(";"))
        assertFalse(naturalSpoken.contains("..."))

        assertEquals("Aoede", VoiceConfig.GEMINI_VOICE_NAME)
        assertEquals("Aoede", VoiceBehaviorSettings.voiceName)
        assertTrue(VoiceBehaviorSettings.startStreamingOnFirstChunk)

        val serverInterruptedCalled = AtomicBoolean(false)
        val playbackManager = AudioPlaybackManager(
            context = context,
            onPlaybackStarted = {},
            onPlaybackFinished = {}
        )
        val liveManager = LiveSessionManager(
            context = context,
            toolEngine = ToolExecutionEngine(context),
            onAudioOut = { genId, data -> playbackManager.enqueueChunk(genId, data) },
            onTurnComplete = { genId -> playbackManager.markTurnComplete(genId) },
            onServerInterrupted = {
                serverInterruptedCalled.set(true)
                playbackManager.interruptAndFlush()
            },
            onStateChange = {}
        )

        playbackManager.enqueueChunk(1L, ByteArray(4800) { 12 })
        liveManager.notifyBargeIn()

        assertTrue(serverInterruptedCalled.get())
        assertEquals(0, playbackManager.getQueueSize())
        assertEquals(RafelVoiceState.INTERRUPTED, liveManager.rafelState.value)
        playbackManager.release()
    }

    @Test
    fun testConsistentPersonalityAcrossLiveRestAndOfflineModels() {
        val liveInstruction = RafelPersona.buildDynamicSystemInstruction(
            latestUserMessage = "Aaj mood kharab hai",
            recentHistory = emptyList(),
            relevantMemorySummary = "",
            includeVoiceDeliveryRules = true
        )
        val restInstruction = RafelPersona.buildDynamicSystemInstruction(
            latestUserMessage = "Aaj mood kharab hai",
            recentHistory = emptyList(),
            relevantMemorySummary = "",
            includeVoiceDeliveryRules = false
        )

        assertTrue(liveInstruction.contains("Detected User Mood: SAD"))
        assertTrue(restInstruction.contains("Detected User Mood: SAD"))
        assertTrue(liveInstruction.contains("HUMAN-LIKE VOICE DELIVERY & PROSODY"))

        val offlineReply = PersonaSettings.generateContextualReply("Aaj mood kharab hai")
        assertTrue(
            offlineReply.contains("Kya hua", ignoreCase = true) ||
                offlineReply.contains("kya ho gaya", ignoreCase = true) ||
                offlineReply.contains("mann", ignoreCase = true) ||
                offlineReply.contains("Batao", ignoreCase = true)
        )
    }

    private data class ScenarioSpec(
        val id: Int,
        val category: String,
        val turns: List<String>
    )

    /**
     * Tests 30 difficult real-world multi-turn scenarios across all 11 required categories
     * and audits them using the independent ConversationQualityAuditor.
     */
    @Test
    fun test30DifficultRealWorldScenarios_IndependentAuditAndRepetitionAnalysis() = runBlocking {
        val scenarios = listOf(
            // 1-3: Casual daily conversations
            ScenarioSpec(1, "Casual_EveningChaiAndRain", listOf(
                "Hey Rafel, kaisi ho aaj?",
                "Aaj sham ko bas ghar pe baitha hu aur adrak wali chai pi raha hu.",
                "Haan yaar, barish ka mausam ho raha hai bahar.",
                "Aise mausam mein kaam karne ka bilkul mann nahi karta."
            )),
            ScenarioSpec(2, "Casual_LazySundayAndMusic", listOf(
                "Aur batao kya chal raha hai?",
                "Aaj din bhar bas gaane sunne ka dil kar raha hai.",
                "Raat ko Maggi ya pizza khane ka mann hai.",
                "Abhi thoda aaram karta hu, baad mein baat karte hain."
            )),
            ScenarioSpec(3, "Casual_MorningTrafficAndCoffee", listOf(
                "Namaste, kaisi ho?",
                "Aaj mausam kafi thanda ho gaya hai.",
                "Chai pi li tumne?",
                "Tumne khana khaya aaj?"
            )),
            // 4-6: Emotional conversations
            ScenarioSpec(4, "Emotional_UnappreciatedAtWork", listOf(
                "Yaar aaj mood kharab hai bahut.",
                "Office mein pure din itni mehnat ki, par kisi ne notice hi nahi kiya.",
                "Kabhi-kabhi lagta hai akele hi sab sambhalna padta hai.",
                "Tumse baat karke thoda halka lag raha hai waise."
            )),
            ScenarioSpec(5, "Emotional_ExhaustionAndBurnout", listOf(
                "Bahut thak gaya hu aaj, bilkul energy nahi bachi.",
                "Pure din office mein bas meetings hi chalti rahi.",
                "Abhi thoda paani pi ke shant baitha hu.",
                "Aaj ka din sach mein kafi exhausting tha."
            )),
            ScenarioSpec(6, "Emotional_InterviewAnxietyToPromotionJoy", listOf(
                "Agle hafte mera interview hai, tension ho rahi hai.",
                "Dar lag raha hai ki agar tayari puri nahi hui toh kya hoga.",
                "Arre wah! Aaj mujhe promotion mil gaya!",
                "Aaj ka din bahut mast gaya, main bahut khush hu."
            )),
            // 7-9: Jokes and teasing
            ScenarioSpec(7, "Jokes_SmartBanterAndBadam", listOf(
                "Tu toh badi smart banti hai haha!",
                "Lagta hai aaj subah extra badam khaye hain tumne lol.",
                "Waah beta, mujhe hi taane maar rahi ho ab?",
                "Mazaak kar raha tha yaar, bura maan gayi kya haha."
            )),
            ScenarioSpec(8, "Jokes_LazinessOlympics", listOf(
                "Main aaj din bhar bas so raha tha, full aalas.",
                "Achha bata, agar aalas ka Olympic hota toh mera kya hota haha?",
                "Sahi kaha, gold medal toh pakka mera hi hota lol.",
                "Chal maan liya tera sense of humour theek-thaak hai."
            )),
            ScenarioSpec(9, "Jokes_FenkuFriendsAndRoast", listOf(
                "Mere dost bolte hain main bahut bada fenku hu lol.",
                "Tu bhi unhi ki team mein shamil ho gayi lagta hai haha.",
                "Zyada oversmart mat ban ab haha.",
                "Chalo bye, hasa diya tune aaj!"
            )),
            // 10-12: Arguments and disagreement
            ScenarioSpec(10, "Disagreement_ExamWithoutStudying", listOf(
                "Mujhe lagta hai bina padhe exam dena sabse best idea hai, tumhe kya lagta hai?",
                "Main nahi manta, raat bhar 3 baje tak jaag ke sab ho jata hai.",
                "Neend zaruri nahi hoti yaar, coffee pi ke kaam chal jata hai.",
                "Chalo maan liya tumhari baat, abhi baith ke padh leta hu."
            )),
            ScenarioSpec(11, "Disagreement_ChaiVsCoffeeAndBunking", listOf(
                "Waise chai se achhi coffee hoti hai, agree karti ho na?",
                "Aaj ke lectures bhi bunk kar deta hu, sahi hai na?",
                "Yaar har baat mein ulta kyun bolti ho, mera plan best hai.",
                "Achha matlab tumhe lagta hai ye aalas ka bahana hai?"
            )),
            ScenarioSpec(12, "Disagreement_LastHourDeadlineAndHotelBooking", listOf(
                "Bilkul nahi, main bas smart work mein believe karta hu.",
                "Tumhe kya lagta hai, deadline se ek ghante pehle kaam shuru karna sahi hai?",
                "Tumhe kya lagta hai, bina hotel book kiye seedha Manali nikal jana sahi hai?",
                "Sahi keh rahi ho, pehle se booking kar lena behtar rahega."
            )),
            // 13-15: Sudden topic changes
            ScenarioSpec(13, "TopicShift_CollegeToMovieToBiryani", listOf(
                "Aaj college mein subah se bas lectures hi chalte rahe.",
                "Canteen mein doston ke saath samosa khaya tab jaake jaan aayi.",
                "Waise chhodo college ki baat, aaj koi badhiya movie dekhne ka mann hai.",
                "Baat badalte hain, raat ke khane mein biryani mangane ka soch raha hu."
            )),
            ScenarioSpec(14, "TopicShift_CricketToCodingToCapital", listOf(
                "Achha suno, kal ka cricket match dekha tha kya?",
                "Last over mein jo six pada, maza hi aa gaya!",
                "Chhodo ye batao, Kotlin mein ek app bana raha tha main.",
                "Bharat ki rajdhani kya hai?"
            )),
            ScenarioSpec(15, "TopicShift_TripFlakeToBiryaniWeekend", listOf(
                "Doston ke saath pichle mahine pahadon pe jane ka plan bana tha.",
                "Par hamesha ki tarah aakhiri time pe do log mukar gaye.",
                "Chhodo woh baat, biryani ke bina weekend adhura lagta hai.",
                "Chalo koi nahi, is weekend aas-paas hi ghoom aaunga."
            )),
            // 16-18: Incomplete messages & ambiguous utterances
            ScenarioSpec(16, "Incomplete_TrailingThoughtsToBossLeave", listOf(
                "Matlab main soch raha tha ki...",
                "Woh kal jo...",
                "Actually baat ye hai...",
                "Haan wahi, office mein boss se baat karni thi chhutti ke liye."
            )),
            ScenarioSpec(17, "Incomplete_HesitationChain", listOf(
                "Pata hai aaj kya...",
                "Main keh raha tha ki...",
                "Par woh...",
                "Chalo theek hai, kal subah hi baat karunga unse."
            )),
            ScenarioSpec(18, "Incomplete_AmbiguousPronounsAndClarification", listOf(
                "Woh wala le lu ya dusra sahi rahega?",
                "Achha haan, main naye headphones aur earbuds ki baat kar raha tha, gaane sunne ke liye.",
                "Usne phir wahi kiya aaj.",
                "Mera dost aakhiri time pe trip se mukar gaya."
            )),
            // 19-21: Short replies ("hmm", "achha", "theek hai", etc.)
            ScenarioSpec(19, "ShortReplies_AfterCodingBug", listOf(
                "Ek chhote se bug ke peeche do ghante nikal gaye aaj.",
                "hmm",
                "achha",
                "theek hai"
            )),
            ScenarioSpec(20, "ShortReplies_AfterProjectDeadline", listOf(
                "Aaj college ka project deadline hai mera.",
                "Subah se usi ke diagrams bana raha tha.",
                "kuch nahi",
                "haan"
            )),
            ScenarioSpec(21, "ShortReplies_MinimalistResponses", listOf(
                "Bore ho raha hu.",
                "nahi",
                "pata nahi",
                "sahi hai"
            )),
            // 22-24: Personal memories
            ScenarioSpec(22, "PersonalMemory_NameAndCityRecall", listOf(
                "Hello Rafel! Mera naam Kabir hai.",
                "Main Pune mein rehta hu aur architect hu.",
                "Aaj sham ko bas ghar pe baitha hu aur adrak wali chai pi raha hu.",
                "Mera naam kya hai, yaad hai tumhe?"
            )),
            ScenarioSpec(23, "PersonalMemory_FoodPreferenceAndRecall", listOf(
                "Mera naam Meera hai.",
                "Mujhe Hyderabadi biryani bahut pasand hai.",
                "Aaj mausam kafi thanda ho gaya hai.",
                "Mera naam kya hai, batao?"
            )),
            ScenarioSpec(24, "PersonalMemory_DeveloperContextAcrossTurns", listOf(
                "Hello Rafel! Mera naam Aarav hai.",
                "Mujhe garam masala chai bahut pasand hai.",
                "Dimag kharab ho gaya ek null pointer bug ke chakkar mein!",
                "Mera naam kya hai, yaad hai tumhe?"
            )),
            // 25-26: Technical discussions
            ScenarioSpec(25, "Technical_KotlinCoroutinesAndFlow", listOf(
                "Explain how Kotlin coroutines work.",
                "Jetpack Compose mein StateFlow aur SharedFlow mein kya farq hai?",
                "WebSocket aur REST API mein live audio ke liye kaunsa behtar hai?",
                "Chalo ab wapas code dekhne ja raha hu, baad mein baat karte hain."
            )),
            ScenarioSpec(26, "Technical_DebuggingNullPointerToBreak", listOf(
                "Dimag kharab ho raha hai, code chal hi nahi raha!",
                "Kotlin mein ek app bana raha tha, null pointer aa raha hai.",
                "Soch raha hu dus minute aankhein band karke baithu.",
                "Phir thodi der balkoni mein khade hoke chai pi."
            )),
            // 27-28: Questions requiring direct answers
            ScenarioSpec(27, "DirectQuestions_FactsAndMath", listOf(
                "Bharat ki rajdhani kya hai?",
                "What is the capital of France?",
                "What is 15 + 27?",
                "Tum sach mein insaan ho kya?"
            )),
            ScenarioSpec(28, "DirectQuestions_PlayfulHypotheticals", listOf(
                "Agar main kal subah alien ban jaun toh kya karogi?",
                "Soch raha hu chaand pe ek chai ki tapri khol lu!",
                "Kya robot ko bhi raat ko sapne aate hain?",
                "Zombie apocalypse aa gaya toh sabse pehle kya bachana chahiye?"
            )),
            // 29-30: Conversations where the user explicitly does NOT want advice
            ScenarioSpec(29, "NoAdviceWanted_WorkVenting", listOf(
                "Yaar aaj office mein bahut bura din tha.",
                "Mujhe abhi koi salah nahi chahiye, bas sun lo.",
                "Koi tip mat dena yaar, bas mann halka kar raha hu.",
                "Tumse baat karke thoda halka lag raha hai waise."
            )),
            ScenarioSpec(30, "NoAdviceWanted_FriendletDownVenting", listOf(
                "Mera dost phir se aakhiri waqt pe plan cancel kar gaya.",
                "Advice mat do, bas bata raha hu aaj kya hua.",
                "Bina gyaan diye bas sunne ke liye shukriya.",
                "Chalo ab sone ja raha hu, good night."
            ))
        )

        assertEquals("Must test at least 30 multi-turn scenarios", 30, scenarios.size)

        val allAuditRecords = mutableListOf<ConversationQualityAuditor.TurnAuditRecord>()

        for (scenario in scenarios) {
            conversationManager.clearAllHistory()
            println("\n--- SCENARIO #${scenario.id}: ${scenario.category} ---")
            for ((turnIdx, userMsg) in scenario.turns.withIndex()) {
                val priorTurns = conversationManager.messages.value
                val reply = conversationManager.generateNaturalReply(userMsg, apiKey = "")
                println("  T${turnIdx + 1} User : $userMsg")
                println("  T${turnIdx + 1} Rafel: $reply (Intent=${NaturalConversationEngine.analyzeTurn(userMsg, priorTurns).semanticIntent}, Act=${NaturalConversationEngine.analyzeTurn(userMsg, priorTurns).discourseAct})")

                val record = ConversationQualityAuditor.auditTurn(
                    scenarioId = scenario.id,
                    scenarioCategory = scenario.category,
                    turnIndex = turnIdx + 1,
                    userMessage = userMsg,
                    rafelResponse = reply,
                    priorHistory = priorTurns
                )
                allAuditRecords.add(record)

                assertTrue(
                    "Technical validation failed in Scenario #${scenario.id} T${turnIdx + 1}: ${record.technicalIssues}",
                    record.technicalPass
                )
                assertTrue(
                    "Semantic validation failed in Scenario #${scenario.id} T${turnIdx + 1}: ${record.semanticIssues} (User='$userMsg' -> Rafel='$reply')",
                    record.semanticPass
                )
            }
        }

        val summary = ConversationQualityAuditor.summarizeCorpus(allAuditRecords)
        println("\n================ INDEPENDENT 30-SCENARIO AUDIT SUMMARY ================")
        println("Total Scenarios           : ${summary.totalScenarios}")
        println("Total Turns               : ${summary.totalTurns}")
        println("Technical Pass            : ${summary.technicalPassCount}/${summary.totalTurns}")
        println("Semantic & Anti-Echo Pass : ${summary.semanticPassCount}/${summary.totalTurns}")
        println("Crutch Phrase Counts      : ${summary.crutchPhraseCounts}")
        println("Total Crutch Occurrences  : ${summary.totalCrutchOccurrences}")
        println("Echoed User 4-Gram Turns  : ${summary.echoedTurnCount}")
        println("4-Gram Repetition Rate    : ${"%.2f".format(summary.repeatedFourGramRatePercent)}%")
        println("Question-Ending Ratio     : ${"%.2f".format(summary.questionEndingRatePercent)}%")
        println("Top 2-Word Openers        : ${summary.topOpeners}")
        println("Flagged Weak Turns        : ${summary.flaggedWeakTurns.size}")
        summary.flaggedWeakTurns.forEach { weak ->
            println("  [WEAK TURN S#${weak.scenarioId} T${weak.turnIndex}] User='${weak.userMessage}' -> Rafel='${weak.rafelResponse}' | Notes=${weak.honestCritiqueNotes} | Semantic=${weak.semanticIssues}")
        }
        println("=======================================================================")

        assertEquals("All turns must pass technical checks", summary.totalTurns, summary.technicalPassCount)
        assertEquals("All turns must pass semantic & anti-paraphrase checks", summary.totalTurns, summary.semanticPassCount)
        assertEquals("Zero overused crutch phrases ('sahi hai', 'chalo badhiya', 'samajh gayi', 'sahi mein', 'bilkul', 'ye hui na baat') allowed across corpus", 0, summary.totalCrutchOccurrences)
        assertEquals("Zero verbatim 4-gram user echoing allowed across corpus", 0, summary.echoedTurnCount)
    }
}

