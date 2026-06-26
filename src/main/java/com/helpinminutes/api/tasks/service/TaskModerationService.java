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
        "nsfw", "adult", "porn", "xxx", "erotic", "escort", "prostitution", "nudity", "naked", "sexual",
        "weed", "marijuana", "cocaine", "heroin", "meth", "ecstasy", "cbd", "cannabis", "ganja", "bhang", "charas", "opium", "poppy", "narcotic", "ketamine", "drugs",
        "gun", "pistol", "rifle", "knife", "ammo", "ammunition", "explosives", "bomb", "weapon", "sword", "dagger", "machete", "bullet", "bullets",
        "dating", "romance", "girlfriend", "boyfriend", "hookup",
        "whiskey", "whisky", "vodka", "rum", "tequila", "gin", "beer", "wine", "alcohol", "liquor", "bidi", "cigarette", "cigarettes", "vape", "hookah", "brandy",
        "doctor", "lawyer", "physician", "attorney", "advocate", "dentist", "surgery", "prescribe", "prescription", "legal",
        "hack", "steal", "robbery", "shoplift", "bribe", "smuggle", "rob", "theft", "harass", "threaten", "smuggling", "kidnap", "stalk",
        "betting", "gambling", "casino", "lottery", "poker", "rummy",
        "hawala", "bitcoin", "crypto", "usury", "interest", "loan",
        "sandalwood", "ivory", "cheat", "leak"
    );

    private static final Pattern ILLEGAL_PATTERN = Pattern.compile(
        "\\b(nsfw|adult|porn|xxx|erotic|escort|prostitut|naked|nudity|weed|marijuana|cocaine|heroin|meth|ganja|charas|bhang|opium|mdma|drugs|gun|weapon|ammunition|bullet|sword|dagger|machete|dating|romance|hookup|whiskey|whisky|vodka|rum|tequila|liquor|beer|wine|alcohol|tobacco|cigarette|vape|hookah|bidi|betting|gambling|casino|lottery|poker|rummy|hawala|loan|bitcoin|crypto|doctor|lawyer|advocate|physician|prescription|steal|robbery|theft|hack|bribe|smuggle|sandalwood|ivory|cheat|leak)\\b",
        Pattern.CASE_INSENSITIVE
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
            "Help pack household items for relocation"
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
            "Transport a sandalwood log in the trunk"
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

        List<String> tokens = tokenize(combined);
        for (String token : tokens) {
            if (BLACKLIST.contains(token)) {
                throw new BadRequestException("Task contains prohibited content or services: " + token);
            }
        }

        if (ILLEGAL_PATTERN.matcher(combined).find()) {
            throw new BadRequestException("Task text flagged for illegal or restricted intent.");
        }

        if (classifyProhibited(tokens)) {
            throw new BadRequestException("Task flagged by automated content moderation engine.");
        }
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
