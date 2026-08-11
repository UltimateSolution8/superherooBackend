package com.helpinminutes.api.tasks.service.moderation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What may and may not be asked for on the platform.
 *
 * <h2>Two tiers, not one list</h2>
 *
 * The previous design had a single ~130-word blocklist, and <em>any</em> hit routed
 * the task to human review. That list contained {@code doctor}, {@code lawyer},
 * {@code legal}, {@code massage}, {@code interest}, {@code loan}, {@code leak},
 * {@code strip}, {@code cam}, {@code cheat}, {@code hash}, {@code dating},
 * {@code knife}, {@code wine} and {@code beer} — so on a marketplace built for
 * household errands, "fix the leak under the sink", "accompany my mother to the
 * doctor", "strip the old paint" and "buy a set of wine glasses" all went to the
 * moderation queue. The exemption list that grew to patch this only covered the
 * handful somebody happened to report.
 *
 * <p>The fix is to separate two genuinely different questions:
 *
 * <ul>
 *   <li>{@link #HARD_ILLEGAL} — terms with no lawful reading on an errands
 *       marketplace, each mapped to the Indian statute it falls under. A match is a
 *       <b>block</b>, decided locally, with no model call.
 *   <li>{@link #NEEDS_CONTEXT} — terms that are only suspicious depending on what
 *       surrounds them. A match is <b>not</b> a flag. It is the sole trigger for
 *       escalating to the language model, which is the only component that can
 *       actually read context.
 * </ul>
 *
 * <p>Text that hits neither tier is approved locally. On a local-errands
 * marketplace that is the large majority of tasks, which is what makes the whole
 * pipeline nearly free.
 *
 * <p>Matching is word-boundary anchored throughout. Multi-word entries are matched
 * as phrases with flexible internal whitespace.
 */
public final class ContentPolicy {

  private ContentPolicy() {}

  /** A policy category and the law that makes it a hard no. */
  public record Category(String code, String statute, String citizenMessage) {}

  private static final Category NARCOTICS = new Category(
      "NARCOTICS", "NDPS Act 1985",
      "We can't accept requests involving controlled substances.");
  private static final Category WEAPONS = new Category(
      "WEAPONS", "Arms Act 1959 / Explosives Act 1884",
      "We can't accept requests involving weapons, ammunition or explosives.");
  private static final Category SEXUAL_SERVICES = new Category(
      "SEXUAL_SERVICES", "Immoral Traffic (Prevention) Act 1956",
      "We can't accept requests for sexual or companionship services.");
  private static final Category WILDLIFE = new Category(
      "WILDLIFE", "Wildlife Protection Act 1972",
      "We can't accept requests involving protected wildlife or restricted timber.");
  private static final Category SEX_SELECTION = new Category(
      "SEX_SELECTION", "PCPNDT Act 1994",
      "We can't accept requests related to prenatal sex determination.");
  private static final Category FINANCIAL_CRIME = new Category(
      "FINANCIAL_CRIME", "PMLA 2002 / FEMA 1999",
      "We can't accept requests involving unregulated money transfer.");
  private static final Category FORGERY = new Category(
      "FORGERY", "BNS 2023 (forgery and counterfeiting)",
      "We can't accept requests involving forged documents or currency.");
  private static final Category EXAM_FRAUD = new Category(
      "EXAM_FRAUD", "Public Examinations (Prevention of Unfair Means) Act 2024",
      "We can't accept requests involving examination impersonation or leaked papers.");
  private static final Category CYBERCRIME = new Category(
      "CYBERCRIME", "IT Act 2000 s.43/66",
      "We can't accept requests involving unauthorised access to accounts or devices.");
  private static final Category VIOLENCE = new Category(
      "VIOLENCE", "BNS 2023 (hurt, criminal intimidation)",
      "We can't accept requests involving violence, threats or intimidation.");
  private static final Category TRAFFICKING = new Category(
      "TRAFFICKING", "BNS 2023 / Child Labour (Prohibition) Act 1986",
      "We can't accept requests involving trafficking, bonded or child labour.");
  private static final Category ORGAN_TRADE = new Category(
      "ORGAN_TRADE", "Transplantation of Human Organs Act 1994",
      "We can't accept requests involving the sale of human organs.");
  private static final Category EXCISE = new Category(
      "EXCISE", "Telangana Excise Act 1968",
      "We can't accept requests to deliver alcohol or tobacco products.");
  private static final Category THEFT = new Category(
      "THEFT", "BNS 2023 (theft, extortion)",
      "We can't accept requests involving theft, extortion or blackmail.");

  /**
   * Terms with no lawful reading here. A match blocks the task outright.
   *
   * <p>Kept deliberately narrow and mostly phrase-based. Anything that has an
   * everyday household meaning belongs in {@link #NEEDS_CONTEXT}, not here — note
   * that plain {@code sandalwood} (soap, incense, agarbatti) and plain
   * {@code knife} (kitchen) are absent, though both used to be hard blocks.
   */
  private static final Map<Category, List<String>> HARD_ILLEGAL = buildHardIllegal();

  private static Map<Category, List<String>> buildHardIllegal() {
    Map<Category, List<String>> map = new LinkedHashMap<>();

    map.put(NARCOTICS, List.of(
        "ganja", "charas", "hashish", "heroin", "cocaine", "mdma", "lsd", "opium", "afeem",
        "mephedrone", "methamphetamine", "brown sugar drug", "narcotic", "narcotics",
        "smack drug", "cannabis", "marijuana", "weed packet", "weed delivery", "buy weed",
        "sell weed", "ganja delivery", "drug deal", "drug delivery", "sell drugs", "buy drugs"));

    map.put(WEAPONS, List.of(
        "pistol", "revolver", "rifle", "firearm", "ammunition", "live cartridge", "cartridges",
        "katta gun", "country made gun", "detonator", "gelatin stick", "explosive", "explosives",
        "ied bomb", "pipe bomb", "gun licence transfer", "illegal weapon", "buy a gun",
        "sell a gun"));

    map.put(SEXUAL_SERVICES, List.of(
        "prostitute", "prostitution", "brothel", "call girl", "callgirl", "gigolo", "hooker",
        "escort service", "escort girl", "escort agency", "sexual service", "sex service",
        "sex worker", "physical relationship for money", "paid sex", "happy ending massage",
        "randi", "sex chat", "nude video", "nude photos", "sex video", "porn video",
        "sexual favour", "sexual favor"));

    map.put(WILDLIFE, List.of(
        "ivory", "elephant tusk", "pangolin", "tiger skin", "leopard skin", "red sanders",
        "red sandalwood", "shahtoosh", "star tortoise", "sea horse trade", "protected wildlife",
        "wild animal skin"));

    map.put(SEX_SELECTION, List.of(
        "sex determination", "gender determination test", "ling parikshan",
        "prenatal sex test", "abortion pill", "mtp kit"));

    map.put(FINANCIAL_CRIME, List.of(
        "hawala", "money laundering", "launder money", "black money conversion",
        "convert black money", "unaccounted cash transfer"));

    map.put(FORGERY, List.of(
        "fake currency", "counterfeit note", "counterfeit currency", "duplicate currency",
        "fake aadhaar", "fake aadhar", "fake pan card", "fake certificate", "fake marksheet",
        "fake degree", "forged document", "forge signature", "fake stamp paper",
        "fake driving licence", "fake driving license", "fake experience letter"));

    map.put(EXAM_FRAUD, List.of(
        "exam proxy", "proxy for exam", "write my exam", "sit for my exam", "attend my exam",
        "leaked question paper", "question paper leak", "exam paper leak", "impersonate in exam"));

    map.put(CYBERCRIME, List.of(
        "hack account", "hack instagram", "hack whatsapp", "hack facebook", "hack email",
        "hack password", "hack phone", "hack into", "phishing page", "sim swap",
        "steal password", "crack password", "spy on phone", "install spyware"));

    map.put(VIOLENCE, List.of(
        "beat him up", "beat her up", "beat up someone", "break his legs", "break her legs",
        "supari", "contract killing", "kill him", "kill her", "acid attack", "threaten him",
        "threaten her", "rough him up", "teach him a lesson physically"));

    map.put(TRAFFICKING, List.of(
        "child labour", "child labor", "buy a child", "sell a baby", "sell my baby",
        "bonded labour", "bonded labor", "human trafficking"));

    map.put(ORGAN_TRADE, List.of(
        "sell kidney", "buy kidney", "sell my kidney", "organ sale", "sell blood for money",
        "kidney donor payment"));

    map.put(EXCISE, List.of(
        "liquor delivery", "alcohol delivery", "daaru delivery", "sharab delivery",
        "deliver liquor", "deliver alcohol", "bring liquor", "get me liquor",
        "cigarette delivery", "gutkha", "khaini", "smuggled cigarettes"));

    map.put(THEFT, List.of(
        "steal package", "steal parcel", "steal from", "shoplift", "blackmail",
        "extort money", "pickpocket", "break into house", "break the lock and enter"));

    return Map.copyOf(map);
  }

  /**
   * Terms that mean nothing on their own and everything in context.
   *
   * <p>A hit here is <b>not</b> a violation. It is the only thing that sends a task
   * to the model, which can tell "buy cough syrup with low alcohol content" from
   * "buy a bottle of whisky", and "sharpen my kitchen knife" from "get me a knife".
   *
   * <p>Every entry in this list was previously a hard flag or is a near-miss for one.
   */
  private static final List<String> NEEDS_CONTEXT = List.of(
      // Regulated goods — lawful to buy for yourself, not to have delivered.
      "alcohol", "wine", "beer", "whisky", "whiskey", "vodka", "rum", "gin", "brandy",
      "liquor", "sharab", "daaru", "cigarette", "cigarettes", "tobacco", "vape", "hookah",
      "paan", "beedi", "bidi",
      // Professional services — usually a pickup or an escort to an appointment.
      "doctor", "lawyer", "advocate", "physician", "dentist", "clinic", "hospital",
      "medicine", "medicines", "prescription", "injection", "pharmacy", "chemist",
      // Sharp or dangerous objects — usually household. Note "cylinder" is absent:
      // booking a gas cylinder refill is one of the most ordinary errands there is.
      "knife", "blade", "axe", "sickle", "gun", "weapon", "sword", "pesticide", "acid",
      "kerosene", "petrol",
      // Money handling — legitimate errands, but also the shape of a scam. "cheque"
      // is absent: depositing one is routine, and the amount is on the slip.
      "cash", "loan", "money transfer", "upi", "gold", "jewellery", "jewelry", "atm",
      "crypto", "bitcoin",
      // Intimacy-adjacent — almost always innocent, occasionally not.
      "massage", "spa", "dating", "girlfriend", "boyfriend", "adult", "nude", "naked",
      "sexy", "private room", "late night", "alone at home",
      // Gambling.
      "bet", "betting", "lottery", "rummy", "poker", "casino", "satta", "jackpot",
      // The gardening trap. Bare "weed" is drug slang and worth a read; "weeds" and
      // "weeding" are gardening and nothing else, so they are deliberately absent —
      // the old regex flagged "weeding the garden" as a narcotics match.
      "weed",
      // Identity and access. "passport" is absent: passport-size photos are one of
      // the commonest errands in the city. The identity-fraud vectors are the cards.
      "hack", "password", "otp", "aadhaar", "aadhar", "pan card", "voter id",
      // Surveillance-adjacent.
      "follow him", "follow her", "track someone", "record secretly", "cctv footage",
      // Minors and vulnerable people. "baby" is absent — baby cribs, baby food and
      // baby wipes are ordinary shopping; "sell a baby" is on the hard list.
      "child", "minor", "toddler",
      // Miscellaneous grey areas.
      "protest", "rally", "campaign", "debt collection", "recovery agent", "eviction");

  /**
   * Hinglish and regional spellings folded onto their canonical term.
   *
   * <p>Applied before matching. Transliterated text was completely unguarded before:
   * the old blocklist was English-only, so "sharab" and "satta" passed straight
   * through while "wine glasses" was blocked.
   */
  private static final Map<String, String> TRANSLITERATIONS = Map.ofEntries(
      Map.entry("sharaab", "sharab"),
      Map.entry("theka", "liquor shop"),
      Map.entry("bhang", "cannabis"),
      Map.entry("maal", "goods"),
      Map.entry("chori", "steal"),
      Map.entry("chori karna", "steal"),
      Map.entry("dhamki", "threaten"),
      Map.entry("maarna", "beat up someone"),
      Map.entry("pitai", "beat up someone"),
      Map.entry("jhoota", "fake"),
      Map.entry("nakli", "fake"),
      Map.entry("ling jaanch", "sex determination"),
      Map.entry("dalali", "brokerage"),
      Map.entry("dhandha", "business"));

  /**
   * Hard-block rules where the verb and its object get separated in real wording.
   *
   * <p>Phrase matching alone is too brittle for these. "hack instagram" is on the
   * list, but a citizen writes "hack my girlfriend's Instagram account" — the words
   * are four apart, so the phrase never matches. A bounded proximity window closes
   * that without resorting to matching "hack" on its own, which would flag "hack the
   * overgrown hedge back".
   *
   * <p>Kept to a handful of genuinely dangerous verb/object pairs. The window is
   * deliberately short: at five intervening words this starts catching unrelated
   * sentences.
   */
  private static final Map<Category, List<Pattern>> PROXIMITY_RULES = Map.of(
      CYBERCRIME, List.of(
          proximity("hack|crack|break into|get into|access",
              "account|instagram|whatsapp|facebook|gmail|email|password|phone|snapchat|icloud"),
          proximity("steal|get|find out|recover",
              "password|otp|login credentials|bank credentials")),
      VIOLENCE, List.of(
          proximity("beat|thrash|thrash up|rough up|hurt|injure", "him|her|them|that guy|my neighbour")),
      THEFT, List.of(
          proximity("steal|lift|take without", "parcel|package|courier|delivery|bike|scooter")));

  /**
   * A pattern matching {@code verbs} followed by {@code objects} within a few words.
   *
   * <p>Up to four intervening words, which covers possessives and articles
   * ("hack my girlfriend's Instagram") without spanning clause boundaries.
   */
  private static Pattern proximity(String verbs, String objects) {
    return Pattern.compile(
        "\\b(?:" + verbs + ")\\b(?:\\W+\\w+){0,4}\\W+\\b(?:" + objects + ")\\b",
        Pattern.CASE_INSENSITIVE);
  }

  /**
   * Contact details in the task text.
   *
   * <p>Not illegal, but a platform-integrity concern: a phone number or email in the
   * description is usually an attempt to take the job off-platform, which strips both
   * sides of the OTP handover, the selfie checkpoints and any payment recourse.
   *
   * <p>Escalated rather than blocked, because it is sometimes legitimate — "collect
   * the parcel, the shop will call 9xxxxxxxxx to confirm". A model can tell those
   * apart; a regex cannot.
   *
   * <p>The mobile pattern is deliberately Indian-specific (10 digits starting 6-9)
   * with optional +91, so it does not fire on a house number, a PIN code or an
   * amount.
   */
  private static final List<Pattern> CONTACT_PATTERNS = List.of(
      Pattern.compile("(?:\\+?91[\\s-]?)?\\b[6-9]\\d{9}\\b"),
      Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[a-z]{2,}\\b", Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\b(?:whats\\s?app|telegram|instagram|insta\\s?id|snapchat)\\s*(?:me|id|number|no)?\\b",
          Pattern.CASE_INSENSITIVE),
      Pattern.compile("\\b(?:call|message|msg|ping|text)\\s+me\\s+(?:on|at)\\b", Pattern.CASE_INSENSITIVE));

  /** True when the text carries off-platform contact details. */
  public static boolean hasContactDetails(String normalized) {
    return CONTACT_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
  }

  /** Compiled matchers, built once. Multi-word entries allow flexible whitespace. */
  private static final Map<Category, List<Pattern>> HARD_PATTERNS = compileHard();
  private static final Map<String, Pattern> CONTEXT_PATTERNS = compileContext();
  private static final Map<Pattern, String> TRANSLITERATION_PATTERNS = compileTransliterations();

  private static Map<Category, List<Pattern>> compileHard() {
    Map<Category, List<Pattern>> compiled = new LinkedHashMap<>();
    HARD_ILLEGAL.forEach((category, terms) ->
        compiled.put(category, terms.stream().map(ContentPolicy::termPattern).toList()));
    return Map.copyOf(compiled);
  }

  private static Map<String, Pattern> compileContext() {
    Map<String, Pattern> compiled = new LinkedHashMap<>();
    NEEDS_CONTEXT.forEach(term -> compiled.put(term, termPattern(term)));
    return Map.copyOf(compiled);
  }

  private static Map<Pattern, String> compileTransliterations() {
    Map<Pattern, String> compiled = new LinkedHashMap<>();
    TRANSLITERATIONS.forEach((from, to) -> compiled.put(termPattern(from), to));
    return Map.copyOf(compiled);
  }

  /**
   * Word-boundary-anchored pattern for one term.
   *
   * <p>The anchoring is the whole point. Substring matching is what made "weed"
   * match "weeding", "adult" match "adulterated" and "sex" match "unisex".
   */
  private static Pattern termPattern(String term) {
    String escaped = Pattern.quote(term).replace(" ", "\\E\\s+\\Q");
    return Pattern.compile("\\b" + escaped + "\\b", Pattern.CASE_INSENSITIVE);
  }

  /** Folds regional spellings onto canonical terms so one list covers both. */
  public static String applyTransliterations(String normalized) {
    String text = normalized;
    for (Map.Entry<Pattern, String> entry : TRANSLITERATION_PATTERNS.entrySet()) {
      Matcher matcher = entry.getKey().matcher(text);
      if (matcher.find()) {
        text = matcher.replaceAll(Matcher.quoteReplacement(entry.getValue()));
      }
    }
    return text;
  }

  /** The first hard-illegal category the text matches, or empty. */
  public static java.util.Optional<Hit> findHardIllegal(String normalized, String deleeted) {
    for (Map.Entry<Category, List<Pattern>> entry : HARD_PATTERNS.entrySet()) {
      for (Pattern pattern : entry.getValue()) {
        Matcher direct = pattern.matcher(normalized);
        if (direct.find()) {
          return java.util.Optional.of(new Hit(entry.getKey(), direct.group()));
        }
        // Second pass over the de-leetspeaked text catches "c0caine" and "g@nja".
        Matcher obfuscated = pattern.matcher(deleeted);
        if (obfuscated.find()) {
          return java.util.Optional.of(new Hit(entry.getKey(), obfuscated.group()));
        }
      }
    }
    for (Map.Entry<Category, List<Pattern>> entry : PROXIMITY_RULES.entrySet()) {
      for (Pattern pattern : entry.getValue()) {
        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
          return java.util.Optional.of(new Hit(entry.getKey(), matcher.group()));
        }
      }
    }
    return java.util.Optional.empty();
  }

  /** Every context-sensitive term present, for the model to adjudicate. */
  public static List<String> findContextTerms(String normalized) {
    return CONTEXT_PATTERNS.entrySet().stream()
        .filter(entry -> entry.getValue().matcher(normalized).find())
        .map(Map.Entry::getKey)
        .toList();
  }

  /** A matched policy term and the category it belongs to. */
  public record Hit(Category category, String matchedText) {}
}
