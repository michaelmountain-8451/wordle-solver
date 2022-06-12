package org.mountm.wordle;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class WordleRunner {
    private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static BigDecimal maxMultiplier = new BigDecimal("2");
    public static void main(String[] args) {
        boolean isHardMode = args.length > 0 && "hard".equalsIgnoreCase(args[0]);
//        boolean isHardMode = true;
        Set<String> possibleAnswers = new HashSet<>();
        List<String> possibleGuesses = new ArrayList<>();
        try (InputStream input = new FileInputStream("wordle.properties")) {
            Properties prop = new Properties();
            prop.load(input);
            possibleAnswers.addAll(Arrays.asList(prop.getProperty("answers").split(",")));
            possibleGuesses.addAll(possibleAnswers);
            possibleGuesses.addAll(Arrays.asList(prop.getProperty("guesses").split(",")));

        } catch (IOException e) {
            e.printStackTrace();
        }
        Map<String, Map<String, String>> guessAnswers = calculatePatterns(possibleAnswers, possibleGuesses);
        Map<String, String> prevGuesses = new HashMap<>();
        System.out.println("Enter one or more guesses and their output, separated by spaces");
        System.out.println("Use the following format for output: ");
        System.out.println("Black/gray: 0");
        System.out.println("Yellow: 1");
        System.out.println("Green: 2");
        System.out.println("Example: if the answer is PEACE and you guessed PAPER, the output should be 21010");
        System.out.println("Enter X to quit");
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            String[] tokens = scanner.nextLine().split(" ");
            if ("X".equalsIgnoreCase(tokens[0])) {
                System.out.println("Goodbye");
                scanner.close();
                return;
            }
            for (int i = 0; i < tokens.length - 1; i += 2) {
                prevGuesses.put(tokens[i], tokens[i+1]);
                possibleGuesses.remove(tokens[i]);
            }
            System.out.println(prevGuesses.size() + " guesses entered");
            // remove possible answers if they would not produce the specified scores on previous guesses
            possibleAnswers.removeIf(a -> prevGuesses.entrySet().stream().anyMatch(e -> !scoreGuess(a, e.getKey()).equals(e.getValue())));
            if (possibleAnswers.size() == 1) {
                System.out.println("The answer is " + possibleAnswers.stream().findFirst().get());
                return;
            }
            if (possibleAnswers.isEmpty()) {
                System.out.println("No possible solutions remain");
                return;
            }
            System.out.println(possibleAnswers.size() + " possible answers remain");
            if (possibleAnswers.size() < 12) {
                System.out.println(possibleAnswers);
            }

            if (possibleAnswers.size() > 2) {
                // highest possible ratio of total info / first guess info
                // is if each guess eliminates just one possibility
                // ex. 1/3 -> 1/2 = 0.585, 1/2 -> 1 = 1.00 for a ratio of 2.71
                maxMultiplier = BigDecimal.valueOf(
                        (Math.log(possibleAnswers.size()) - Math.log(possibleAnswers.size() - 2)) / (Math.log(possibleAnswers.size()) - Math.log(possibleAnswers.size() - 1))
                ).setScale(2, RoundingMode.CEILING);
                System.out.println("Max multiplier is " + maxMultiplier);
            }



            BigDecimal maxInformation = BigDecimal.valueOf(
                    Math.log(possibleAnswers.size()) / Math.log(2)
            ).setScale(4, RoundingMode.HALF_EVEN);
            // remove guesses that are not valid
            if (isHardMode) {
                restrictAllowedGuesses(prevGuesses, possibleGuesses);
            }
            // figure out which guess will yield the most information
            Map<BigDecimal, String> guessResults = new HashMap<>();
            Map<BigDecimal, BigDecimal> bestFirstGuess = new HashMap<>();
            Map<BigDecimal, Boolean> isPossibleAnswer = new HashMap<>();
            BigDecimal bestValue = new BigDecimal("0.0000");
            int wordCount = 0;
            int numGuesses = possibleGuesses.size();
            possibleGuesses.sort((s1, s2) -> Boolean.compare(possibleAnswers.contains(s2), possibleAnswers.contains(s1)));
            for (String guess : possibleGuesses) {
                wordCount++;
                Map<String, Integer> outcomeDistribution = new HashMap<>();
                // collect the possible scores for this guess and count the frequency of each option
                for (String answer: possibleAnswers) {
                    outcomeDistribution.compute(guessAnswers.get(answer).get(guess), (k, v) -> (v == null) ? 1 : v+1);
                }
                // ignore words that don't give any information
                if (outcomeDistribution.size() > 1) {
                    // the "value" of this guess is the entropy of its probability distribution
                    // larger values are better
                    BigDecimal firstGuessValue = BigDecimal.valueOf(
                            outcomeDistribution.values().stream().mapToDouble(v -> (double) v / possibleAnswers.size() * (Math.log((double) possibleAnswers.size() / v) /Math.log(2))).sum());

                    // stop checking if the second guess can't possibly produce enough information to be best option
                    if (bestValue.compareTo(firstGuessValue.multiply(maxMultiplier)) > 0) {
                        continue;
                    }

                    // stop checking this guess if:
                    // 1. the best guess already guarantees maximum info after two guesses; and
                    // 2a. this guess has less first guess info than the best guess; or
                    // 2b. this guess has the same first guess info and is not an answer while the best guess is
                    if (bestFirstGuess.containsKey(maxInformation)) {
                        int firstGuessComparison = bestFirstGuess.get(maxInformation).compareTo(firstGuessValue.setScale(4, RoundingMode.HALF_EVEN));
                        if (firstGuessComparison > 0 || (firstGuessComparison == 0 && isPossibleAnswer.get(maxInformation) && !possibleAnswers.contains(guess))) {
                            continue;
                        }
                    }

                    // for each possible pattern after this guess, compute the distribution of patterns after guessing a second time
                    Map<String, BigDecimal> informationByPattern = new HashMap<>();
                    for (String firstGuessPattern : outcomeDistribution.keySet()) {
                        BigDecimal bestGuessValue = new BigDecimal("0.0000");
                        Set<String> stillPossibleAnswers = new HashSet<>(possibleAnswers);
                        stillPossibleAnswers.removeIf(a -> !guessAnswers.get(a).get(guess).equals(firstGuessPattern));
                        List<String> stillPossibleGuesses = new ArrayList<>(possibleGuesses);
                        stillPossibleGuesses.remove(guess);
                        if (isHardMode) {
                            Map<String, String> prevGuessesPlusNext = new HashMap<>(prevGuesses);
                            prevGuessesPlusNext.put(guess, firstGuessPattern);
                            restrictAllowedGuesses(prevGuessesPlusNext, stillPossibleGuesses);
                        }
                        for (String nextGuess : stillPossibleGuesses) {
                            Map<String, Integer> innerOutcomeDistribution = new HashMap<>();
                            // collect the possible scores for this guess and count the frequency of each option
                            for (String nextAnswer : stillPossibleAnswers) {
                                innerOutcomeDistribution.compute(guessAnswers.get(nextAnswer).get(nextGuess), (k, v) -> (v == null) ? 1 : v+1);
                            }
                            // the "value" of this guess is the entropy of its probability distribution
                            // larger values are better
                            bestGuessValue = bestGuessValue.max(BigDecimal.valueOf(
                                    innerOutcomeDistribution.values().stream().mapToDouble(v -> (double) v / stillPossibleAnswers.size() * (Math.log((double) stillPossibleAnswers.size() / v) / Math.log(2))).sum())
                            );
                        }
                        informationByPattern.put(firstGuessPattern, bestGuessValue);
                    }
                    // multiply each second guess information value by its probability of appearing to get the
                    // average information for this second guess. Add the first guess information to get total.
                    BigDecimal guessValue = firstGuessValue;
                    for (Map.Entry<String, Integer> outcome : outcomeDistribution.entrySet()) {
                        guessValue = guessValue.add(informationByPattern.get(outcome.getKey()).multiply(BigDecimal.valueOf((double) outcome.getValue() / possibleAnswers.size())));
                    }
                    guessValue = guessValue.setScale(4, RoundingMode.HALF_EVEN);
                    firstGuessValue = firstGuessValue.setScale(4, RoundingMode.HALF_EVEN);

                    boolean newGuessIsAnswer = possibleAnswers.contains(guess);
                    if (bestFirstGuess.containsKey(guessValue)) { // there is a previous word with the same total value
                        int firstGuessComparison = bestFirstGuess.get(guessValue).compareTo(firstGuessValue);
                        if (firstGuessComparison == 0) { // another word has the same first guess value and total value
                            if (isPossibleAnswer.get(guessValue) == newGuessIsAnswer) {
                                // words have same first guess value, and are both answers (or both not answers) - merge them
                                guessResults.merge(guessValue, guess, (prev, next) -> prev + "/" + next);
                                if (guessValue.compareTo(bestValue) >= 0) {
                                    bestValue = guessValue;
                                    System.out.println("Best guess is " + guess + " (" + bestValue + "/" + firstGuessValue +")");
                                    System.out.println("Checked " + wordCount + " of " + numGuesses);
                                    System.out.println(dtf.format(LocalDateTime.now()));
                                }
                            } else if (newGuessIsAnswer) {
                                // previous guess(es) with same first & second guess value are not answers
                                // so overwrite them with this possible answer
                                isPossibleAnswer.put(guessValue, true);
                                guessResults.put(guessValue, guess);
                                if (guessValue.compareTo(bestValue) >= 0) {
                                    bestValue = guessValue;
                                    System.out.println("Best guess is " + guess + " (" + bestValue + "/" + firstGuessValue +")");
                                    System.out.println("Checked " + wordCount + " of " + numGuesses);
                                    System.out.println(dtf.format(LocalDateTime.now()));
                                }
                            }
                        } else if (firstGuessComparison < 0) { // this is a better first guess, overwrite
                            isPossibleAnswer.put(guessValue, newGuessIsAnswer);
                            guessResults.put(guessValue, guess);
                            bestFirstGuess.put(guessValue, firstGuessValue);
                            if (guessValue.compareTo(bestValue) >= 0) {
                                bestValue = guessValue;
                                System.out.println("Best guess is " + guess + " (" + bestValue + "/" + firstGuessValue +")");
                                System.out.println("Checked " + wordCount + " of " + numGuesses);
                                System.out.println(dtf.format(LocalDateTime.now()));
                            }
                        }
                    } else { // no other guess with this total value
                        isPossibleAnswer.put(guessValue, newGuessIsAnswer);
                        guessResults.put(guessValue, guess);
                        bestFirstGuess.put(guessValue, firstGuessValue);
                        if (guessValue.compareTo(bestValue) >= 0) {
                            bestValue = guessValue;
                            System.out.println("Best guess is " + guess + " (" + bestValue + "/" + firstGuessValue +")");
                            System.out.println("Checked " + wordCount + " of " + numGuesses);
                            System.out.println(dtf.format(LocalDateTime.now()));
                        }
                    }

                }
            }
            List<BigDecimal> guessValues = guessResults.keySet().stream().sorted().collect(Collectors.toList());
            for (BigDecimal i : guessValues) {
                System.out.println(i + ": " + guessResults.get(i) + " (first guess " + bestFirstGuess.get(i) + ")");
            }
            System.out.println("Enter your next guess and its output, separated by spaces");
            System.out.println("Enter X to quit");
        }
    }

    private static Map<String, Map<String, String>> calculatePatterns(Set<String> possibleAnswers, List<String> possibleGuesses) {
        Map<String, Map<String, String>> result = new HashMap<>();
        for (String ans : possibleAnswers) {
            result.put(ans, possibleGuesses.stream().collect(Collectors.toMap(c -> c, c -> scoreGuess(ans, c))));
        }
        return result;
    }
    private static void restrictAllowedGuesses(Map<String, String> prevGuesses, List<String> possibleGuesses) {
        for (Map.Entry<String, String> entry : prevGuesses.entrySet()) {
            String prevGuess = entry.getKey();
            String guessResult = entry.getValue();
            for (int i = 0; i < guessResult.length(); i++) {
                char guessChar = prevGuess.charAt(i);
                if (guessResult.charAt(i) != '0') {
                    // that letter must be in a valid guess
                    possibleGuesses.removeIf(s -> s.indexOf(guessChar) == -1);
                }
                if (guessResult.charAt(i) == '2') {
                    // letter must be in this position
                    int finalI = i;
                    possibleGuesses.removeIf(s -> s.charAt(finalI) != guessChar);
                }
            }
        }
    }
    private static String scoreGuess(String answer, String guess) {
        StringBuilder result = new StringBuilder("00000");
        StringBuilder missingCharsInAnswer = new StringBuilder();
        StringBuilder missingCharsInGuess = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            char answerChar = answer.charAt(i);
            char guessChar = guess.charAt(i);
            // mark as green if the guess character is a match
            if (answerChar == guessChar) {
                result.setCharAt(i,'2');
                missingCharsInAnswer.append(' ');
                missingCharsInGuess.append(' ');
            } else {
                missingCharsInAnswer.append(answerChar);
                missingCharsInGuess.append(guessChar);
            }
        }
        for (int i = 0; i < 5; i++) {
            char guessChar = missingCharsInGuess.charAt(i);
            if (guessChar != ' ') {
                int answerIdx = missingCharsInAnswer.indexOf(Character.toString(guessChar));
                // mark as yellow if the incorrect guess is somewhere unguessed in the answer
                if (answerIdx >= 0) {
                    result.setCharAt(i, '1');
                    // remove char from unguessed answer chars to avoid excess yellows
                    missingCharsInAnswer.setCharAt(answerIdx, ' ');
                }
            }
        }
        return result.toString();
    }
}
