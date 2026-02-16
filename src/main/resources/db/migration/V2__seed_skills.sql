INSERT INTO skills (category, name)
VALUES
  ('Errands', 'Document Pickup/Drop'),
  ('Errands', 'Medicine Pickup'),
  ('Tech Setup', 'Wi-Fi Router Setup'),
  ('Tech Setup', 'Printer Setup'),
  ('Handyman Micro', 'Drilling (1-2 holes)'),
  ('Handyman Micro', 'Switch/Socket Replace (single)'),
  ('Care', 'Senior Companion (short)')
ON CONFLICT DO NOTHING;
