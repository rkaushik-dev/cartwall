-- Let a buyer cap the spots a day.
--
-- Twelve a week placed into a tight week used to pile up on whichever day had
-- room. A buyer who wrote "no more than three a day" on the order had to be
-- watched by hand. Zero is no cap, which is what every line on file had before.

ALTER TABLE sales_line ADD COLUMN daily_cap INTEGER DEFAULT 0 NOT NULL;
