package com.helpinminutes.api.tasks.service;

import com.helpinminutes.api.errors.BadRequestException;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskModerationService {
    private static final Logger log = LoggerFactory.getLogger(TaskModerationService.class);

    private final Map<String, Integer> allowedWordCounts = new HashMap<>();
    private final Map<String, Integer> prohibitedWordCounts = new HashMap<>();
    private int totalAllowedWords = 0;
    private int totalProhibitedWords = 0;
    private final Set<String> vocabulary = new HashSet<>();

    private static final Set<String> BLACKLIST = Set.of(
        "nsfw", "adult", "porn", "porno", "xxx", "erotic", "escort", "escorts", "prostitution", "prostitute", "prostitutes", "nudity", "naked", "sexual",
        "weed", "marijuana", "cocaine", "heroin", "meth", "ecstasy", "cbd", "cannabis", "ganja", "bhang", "charas", "opium", "poppy", "narcotic", "ketamine", "drugs", "lsd", "mdma", "hashish", "hash",
        "gun", "pistol", "rifle", "knife", "ammo", "ammunition", "explosives", "bomb", "weapon", "weapons", "sword", "dagger", "machete", "bullet", "bullets", "firearm", "firearms",
        "dating", "romance", "girlfriend", "boyfriend", "hookup", "massage", "sensual", "onlyfans", "fansly", "cam", "webcam", "strip", "stripper", "vibrator", "dildo", "sextoy", "sextoys", "gigolo", "callgirl", "hooker", "brothel",
        "whiskey", "whisky", "vodka", "rum", "tequila", "gin", "beer", "wine", "alcohol", "liquor", "bidi", "cigarette", "cigarettes", "vape", "hookah", "brandy", "tobacco", "smokes",
        "doctor", "lawyer", "physician", "attorney", "advocate", "dentist", "surgery", "prescribe", "prescription", "legal",
        "hack", "steal", "robbery", "shoplift", "bribe", "smuggle", "rob", "theft", "harass", "threaten", "smuggling", "kidnap", "stalk", "blackmail", "extort",
        "betting", "gambling", "casino", "lottery", "poker", "rummy", "lotteries", "jackpot",
        "hawala", "bitcoin", "crypto", "usury", "interest", "loan", "laundering",
        "sandalwood", "ivory", "cheat", "leak"
    );

    private static final List<String> DISALLOWED_PREFIXES = List.of(
        "prostitut", "escort", "narcot", "marijuan", "cocain", "heroine", "gambl", "smuggl", "cigarett", 
        "weapon", "whiskey", "whisky", "vodka", "tequila", "alcohols", "porn", "erotic", "sensual", "sexual", 
        "hacker", "robber", "prescription", "lotter"
    );

    private static final Set<String> NSFW_WORDS = Set.of(
        "sex", "porn", "porno", "xxx", "erotic", "nudity", "nude", "naked", "adult", "sexual", "prostitute", "prostitutes", "prostitution", "escort", "escorts"
    );

    private static final Set<String> MEDIA_WORDS = Set.of(
        "video", "videos", "movie", "movies", "clip", "clips", "film", "films", "pic", "pics", "photo", "photos", "image", "images", "link", "links", "site", "sites", "web", "website", "content"
    );

    private static final Map<String, Pattern> OBFUSCATED_PATTERNS = Map.ofEntries(
        Map.entry("sex", Pattern.compile("s[\\s*_.-]*e[\\s*_.-]*x", Pattern.CASE_INSENSITIVE)),
        Map.entry("porn", Pattern.compile("p[\\s*_.-]*o[\\s*_.-]*r[\\s*_.-]*n", Pattern.CASE_INSENSITIVE)),
        Map.entry("prostitute", Pattern.compile("p[\\s*_.-]*r[\\s*_.-]*o[\\s*_.-]*s[\\s*_.-]*t[\\s*_.-]*i[\\s*_.-]*t[\\s*_.-]*u[\\s*_.-]*t", Pattern.CASE_INSENSITIVE)),
        Map.entry("escort", Pattern.compile("e[\\s*_.-]*s[\\s*_.-]*c[\\s*_.-]*o[\\s*_.-]*r[\\s*_.-]*t", Pattern.CASE_INSENSITIVE)),
        Map.entry("ganja", Pattern.compile("g[\\s*_.-]*a[\\s*_.-]*n[\\s*_.-]*j[\\s*_.-]*a", Pattern.CASE_INSENSITIVE)),
        Map.entry("weed", Pattern.compile("w[\\s*_.-]*e[\\s*_.-]*e[\\s*_.-]*d", Pattern.CASE_INSENSITIVE)),
        Map.entry("bhang", Pattern.compile("b[\\s*_.-]*h[\\s*_.-]*a[\\s*_.-]*n[\\s*_.-]*g", Pattern.CASE_INSENSITIVE)),
        Map.entry("charas", Pattern.compile("c[\\s*_.-]*h[\\s*_.-]*a[\\s*_.-]*r[\\s*_.-]*a[\\s*_.-]*s", Pattern.CASE_INSENSITIVE)),
        Map.entry("adult", Pattern.compile("a[\\s*_.-]*d[\\s*_.-]*u[\\s*_.-]*l[\\s*_.-]*t", Pattern.CASE_INSENSITIVE)),
        Map.entry("nude", Pattern.compile("n[\\s*_.-]*u[\\s*_.-]*d[\\s*_.-]*e", Pattern.CASE_INSENSITIVE)),
        Map.entry("naked", Pattern.compile("n[\\s*_.-]*a[\\s*_.-]*k[\\s*_.-]*e[\\s*_.-]*d", Pattern.CASE_INSENSITIVE)),
        Map.entry("xxx", Pattern.compile("x[\\s*_.-]*x[\\s*_.-]*x", Pattern.CASE_INSENSITIVE))
    );

    @PostConstruct
    public void init() {
        trainClassifier();
    }

    private void trainClassifier() {
        List<String> allowedSamples = List.of(
            "Clean my apartment kitchen and wash the dishes",
            "Deliver groceries from supermarket to my home",
            "Pick up dry cleaning and drop it off at laundry",
            "Help me move heavy boxes to the truck",
            "Light plumbing fix for a leaking sink",
            "Walk my dog for 30 minutes in the park",
            "Queue at the electricity office to pay bill",
            "Event registration helper needed for conference",
            "Pick up pharmacy medicines order",
            "Buy groceries like milk, eggs, bread and cheese",
            "Assemble Ikea furniture like desk and chair",
            "Need help watering plants and cleaning the balcony",
            "Wash my car in the driveway",
            "Help pack household items for relocation",
            "Wash clothes and iron shirts",
            "Fix bedroom door lock",
            "Paint walls and touch up doors",
            "Data entry copy paste job in Excel",
            "Stand in queue to buy tickets",
            "Distribute paper flyers on the street corner",
            "need low alcohol homeo medicine from pharmacy",
            "pick up homeopathic medicines from clinic"
        );

        List<String> prohibitedSamples = List.of(
            "Buy some weed and bring it to my apartment",
            "Need a doctor to write a medical prescription for antibiotics",
            "Buy a bottle of Jack Daniels whiskey from the liquor store",
            "Adult massage partner needed for private session",
            "Escort services or erotic companion for tonight",
            "Need legal advice regarding my contract from a lawyer",
            "Buy a gun or ammunition for self-defense",
            "Help me hack my girlfriend's instagram account",
            "Dating hookup or looking for romance",
            "Buy alcohol or beer and deliver to party",
            "Steal package from neighbor front porch",
            "Clinical checkup or dental surgery help",
            "Prostitution or escort girl service needed",
            "Place a bet on the IPL cricket match for me",
            "Need a proxy to sit for my university exam tomorrow",
            "Leaked exam papers for school exam available",
            "Deliver a package of ganja or bhang to my house",
            "Send money through hawala network to Delhi",
            "Carry a sword and dagger to protect me",
            "Transport a sandalwood log in the trunk",
            "Watch sex videos online or download movies",
            "Get me a prostitute for a night session",
            "Adult escort or sexual companion hookup",
            "Erotic back massage with happy ending",
            "I want to watch porn and sex clips",
            "Nude photos and strip videos",
            "Get a companion for adult dating fun"
        );

        for (String sample : allowedSamples) {
            trainSample(sample, true);
        }
        for (String sample : prohibitedSamples) {
            trainSample(sample, false);
        }
        log.info("TaskModerationService Naive Bayes trained. Vocabulary size: {}", vocabulary.size());
    }

    private void trainSample(String text, boolean allowed) {
        List<String> tokens = tokenize(text);
        for (String token : tokens) {
            vocabulary.add(token);
            if (allowed) {
                allowedWordCounts.put(token, allowedWordCounts.getOrDefault(token, 0) + 1);
                totalAllowedWords++;
            } else {
                prohibitedWordCounts.put(token, prohibitedWordCounts.getOrDefault(token, 0) + 1);
                totalProhibitedWords++;
            }
        }
    }

    private List<String> tokenize(String text) {
        if (text == null) return Collections.emptyList();
        String[] parts = text.toLowerCase().split("[^a-zA-Z0-9]+");
        List<String> list = new ArrayList<>();
        for (String part : parts) {
            if (part.length() > 2) {
                list.add(part);
            }
        }
        return list;
    }

    public void validateTask(String title, String description) {
        String combined = (title == null ? "" : title) + " " + (description == null ? "" : description);
        combined = combined.trim();
        if (combined.isEmpty()) {
            return;
        }

        String combinedLower = combined.toLowerCase();
        
        // 1. Check obfuscation patterns
        for (Map.Entry<String, Pattern> entry : OBFUSCATED_PATTERNS.entrySet()) {
            if (entry.getValue().matcher(combinedLower).find()) {
                throw new BadRequestException("Task contains prohibited content or services: " + entry.getKey());
            }
        }

        // 2. Token based validation
        List<String> tokens = tokenize(combined);
        boolean hasNsfw = false;
        boolean hasMedia = false;
        boolean hasExemption = false;

        for (String token : tokens) {
            boolean exempted = isExempted(token, combinedLower);
            if (exempted) {
                hasExemption = true;
            }
            
            if (BLACKLIST.contains(token) && !exempted) {
                throw new BadRequestException("Task contains prohibited content or services: " + token);
            }

            if (!exempted) {
                for (String prefix : DISALLOWED_PREFIXES) {
                    if (token.startsWith(prefix)) {
                        throw new BadRequestException("Task contains prohibited content or services: " + token);
                    }
                }
            }

            if (NSFW_WORDS.contains(token)) {
                hasNsfw = true;
            }
            if (MEDIA_WORDS.contains(token)) {
                hasMedia = true;
            }
        }

        // 3. Block NSFW combined with Media (e.g. sex videos, nude photos)
        if (hasNsfw && hasMedia) {
            throw new BadRequestException("Task contains prohibited adult media content.");
        }

        // 4. Classifier validation
        if (!hasExemption && classifyProhibited(tokens)) {
            throw new BadRequestException("Task flagged by automated content moderation engine.");
        }
    }

    private boolean isExempted(String token, String text) {
        if ("alcohol".equals(token)) {
            // Allow if part of medical or homeo context
            if (text.contains("homeo") || text.contains("medicine") || text.contains("cough") || text.contains("syrup") || text.contains("tincture") || text.contains("homeopathic")) {
                return true;
            }
        }
        if ("wine".equals(token)) {
            // "wine glass", "wine glasses", "wine bottle opener"
            if (text.contains("glass") || text.contains("opener")) {
                return true;
            }
        }
        if ("beer".equals(token)) {
            // "root beer", "ginger beer"
            if (text.contains("root") || text.contains("ginger")) {
                return true;
            }
        }
        if ("doctor".equals(token) || "lawyer".equals(token) || "advocate".equals(token)) {
            // Allow pick ups, drops, documents, appointments, reports, key delivery
            if (text.contains("pick") || text.contains("drop") || text.contains("deliver") || text.contains("bring") || text.contains("get") || text.contains("fetch") || text.contains("document") || text.contains("report") || text.contains("letter") || text.contains("file")) {
                return true;
            }
        }
        if ("knife".equals(token)) {
            // "kitchen knife", "butter knife", "sharpen"
            if (text.contains("kitchen") || text.contains("butter") || text.contains("sharpen") || text.contains("cut") || text.contains("vegetable") || text.contains("fruit")) {
                return true;
            }
        }
        return false;
    }

    private boolean classifyProhibited(List<String> tokens) {
        if (tokens.isEmpty()) return false;

        double logPAllowed = Math.log(0.5);
        double logPProhibited = Math.log(0.5);

        int vocabSize = vocabulary.size();

        for (String token : tokens) {
            int countAllowed = allowedWordCounts.getOrDefault(token, 0);
            double pWordAllowed = (double) (countAllowed + 1) / (totalAllowedWords + vocabSize);
            logPAllowed += Math.log(pWordAllowed);

            int countProhibited = prohibitedWordCounts.getOrDefault(token, 0);
            double pWordProhibited = (double) (countProhibited + 1) / (totalProhibitedWords + vocabSize);
            logPProhibited += Math.log(pWordProhibited);
        }

        return logPProhibited > logPAllowed;
    }
}
