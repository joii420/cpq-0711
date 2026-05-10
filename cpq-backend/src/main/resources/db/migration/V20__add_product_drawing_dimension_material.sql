-- Add drawing_no, dimension, material fields to product table
ALTER TABLE product ADD COLUMN drawing_no VARCHAR(200);
ALTER TABLE product ADD COLUMN dimension VARCHAR(200);
ALTER TABLE product ADD COLUMN material VARCHAR(200);
