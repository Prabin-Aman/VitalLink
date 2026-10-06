"""Seeded foods, per 100 g: (name, kcal, protein, carbs, fat, fibre, typical serving g).

Approximate standard-reference values for common foods. Ids are uuid5 of the name, so the migration
and this file agree without storing ids twice.
"""

import uuid

FOODS: tuple[tuple[str, float, float, float, float, float, float], ...] = (
    ("Greek yoghurt", 73, 9.9, 3.9, 2.0, 0, 170),
    ("Rolled oats", 379, 13.2, 67.7, 6.5, 10.1, 40),
    ("Blueberries", 57, 0.7, 14.5, 0.3, 2.4, 75),
    ("Honey", 304, 0.3, 82.4, 0, 0.2, 21),
    ("Chicken breast, cooked", 165, 31, 0, 3.6, 0, 120),
    ("White rice, cooked", 130, 2.7, 28.2, 0.3, 0.4, 160),
    ("Brown rice, cooked", 123, 2.7, 25.6, 1.0, 1.6, 160),
    ("Avocado", 160, 2.0, 8.5, 14.7, 6.7, 70),
    ("Mixed greens", 20, 1.8, 3.3, 0.3, 1.9, 60),
    ("Whey protein powder", 400, 80, 8, 6, 0, 30),
    ("Banana", 89, 1.1, 22.8, 0.3, 2.6, 118),
    ("Salmon, cooked", 206, 22.1, 0, 12.4, 0, 140),
    ("Sweet potato, baked", 90, 2.0, 20.7, 0.2, 3.3, 150),
    ("Broccoli, cooked", 35, 2.4, 7.2, 0.4, 3.3, 90),
    ("Egg", 143, 12.6, 0.7, 9.5, 0, 50),
    ("Whole wheat bread", 247, 13, 41, 3.4, 7, 32),
    ("Peanut butter", 588, 25, 20, 50, 6, 32),
    ("Apple", 52, 0.3, 13.8, 0.2, 2.4, 180),
    ("Almonds", 579, 21.2, 21.6, 49.9, 12.5, 28),
    ("Milk, 2%", 50, 3.3, 4.8, 2.0, 0, 240),
    ("Cheddar cheese", 403, 24.9, 1.3, 33.1, 0, 28),
    ("Black beans, cooked", 132, 8.9, 23.7, 0.5, 8.7, 130),
    ("Pasta, cooked", 158, 5.8, 30.9, 0.9, 1.8, 140),
    ("Ground beef, 90% lean, cooked", 217, 26.1, 0, 11.7, 0, 100),
    ("Tofu, firm", 144, 15.8, 2.8, 8.7, 2.3, 100),
    ("Olive oil", 884, 0, 0, 100, 0, 14),
    ("Orange", 47, 0.9, 11.8, 0.1, 2.4, 140),
    ("Spinach", 23, 2.9, 3.6, 0.4, 2.2, 30),
    ("Flour tortilla", 304, 8, 50, 8, 3, 45),
    ("Hummus", 166, 7.9, 14.3, 9.6, 6, 30),
)

NAMESPACE = uuid.UUID("6b1f3c1e-6f0e-4d5e-9a52-7a1c2f0e9d11")


def food_id(name: str) -> uuid.UUID:
    return uuid.uuid5(NAMESPACE, name.lower())
