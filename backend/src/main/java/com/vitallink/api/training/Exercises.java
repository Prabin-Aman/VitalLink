package com.vitallink.api.training;

import static com.vitallink.api.training.TrainingDtos.MuscleGroup.arms;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.back;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.chest;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.core;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.full_body;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.glutes;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.legs;
import static com.vitallink.api.training.TrainingDtos.MuscleGroup.shoulders;

import com.vitallink.api.training.TrainingDtos.MuscleGroup;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Seeded exercise catalogue. Ids are permanent; migration V5 inserts the same rows. */
public final class Exercises {

    private Exercises() {}

    public record Exercise(int id, String name, MuscleGroup muscle) {}

    public static final List<Exercise> ALL = List.of(
            new Exercise(1, "Back Squat", legs),
            new Exercise(2, "Front Squat", legs),
            new Exercise(3, "Leg Press", legs),
            new Exercise(4, "Lunge", legs),
            new Exercise(5, "Romanian Deadlift", legs),
            new Exercise(6, "Calf Raise", legs),
            new Exercise(7, "Deadlift", back),
            new Exercise(8, "Barbell Row", back),
            new Exercise(9, "Pull-up", back),
            new Exercise(10, "Lat Pulldown", back),
            new Exercise(11, "Seated Cable Row", back),
            new Exercise(12, "Bench Press", chest),
            new Exercise(13, "Incline Bench Press", chest),
            new Exercise(14, "Dumbbell Fly", chest),
            new Exercise(15, "Push-up", chest),
            new Exercise(16, "Dip", chest),
            new Exercise(17, "Overhead Press", shoulders),
            new Exercise(18, "Lateral Raise", shoulders),
            new Exercise(19, "Face Pull", shoulders),
            new Exercise(20, "Bicep Curl", arms),
            new Exercise(21, "Hammer Curl", arms),
            new Exercise(22, "Tricep Extension", arms),
            new Exercise(23, "Skull Crusher", arms),
            new Exercise(24, "Plank", core),
            new Exercise(25, "Hanging Leg Raise", core),
            new Exercise(26, "Russian Twist", core),
            new Exercise(27, "Hip Thrust", glutes),
            new Exercise(28, "Glute Bridge", glutes),
            new Exercise(29, "Kettlebell Swing", full_body),
            new Exercise(30, "Burpee", full_body));

    /** By lower-case name. */
    public static final Map<String, Exercise> BY_NAME =
            ALL.stream().collect(Collectors.toUnmodifiableMap(e -> e.name().toLowerCase(), Function.identity()));

    public static final Map<Integer, Exercise> BY_ID =
            ALL.stream().collect(Collectors.toUnmodifiableMap(Exercise::id, Function.identity()));
}
