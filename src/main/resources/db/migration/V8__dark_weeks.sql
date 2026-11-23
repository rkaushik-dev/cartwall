-- Let a flight go dark.
--
-- A buyer stretching a budget over a season buys two weeks on and one off, and
-- until now that had to be typed as three lines with three flights. The dark
-- weeks are kept on the line as the Mondays they start on, comma separated, so a
-- line that never goes dark reads back exactly as it did before this ran.

ALTER TABLE sales_line ADD COLUMN dark_weeks VARCHAR(400) DEFAULT '' NOT NULL;
