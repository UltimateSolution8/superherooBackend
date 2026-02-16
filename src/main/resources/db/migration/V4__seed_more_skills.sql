INSERT INTO skills (category, name)
VALUES
  ('Care', 'Bringing child from school'),
  ('Care', 'Elderly check-in call (30 min)'),
  ('Care', 'Senior hospital companion (2 hrs)'),
  ('Care', 'Medicine reminder setup'),
  ('Care', 'Post-surgery short companion'),

  ('Errands', 'Grocery quick pickup'),
  ('Errands', 'Courier booking and handover'),
  ('Errands', 'Forgotten keys delivery'),
  ('Errands', 'Tailor/repair drop and pickup'),
  ('Errands', 'Utility bill payment at counter'),

  ('Home Assist', 'Queue standing service'),
  ('Home Assist', 'Waiting for delivery/technician'),
  ('Home Assist', 'Water can replacement'),
  ('Home Assist', 'Small furniture move (inside home)'),
  ('Home Assist', 'Event setup helper (2 hrs)'),

  ('Tech Setup', 'Smart TV setup'),
  ('Tech Setup', 'OTT app login setup'),
  ('Tech Setup', 'Phone data backup to cloud'),
  ('Tech Setup', 'Video call setup for elders'),
  ('Tech Setup', 'UPI app setup and safety check'),

  ('Handyman Micro', 'Curtain rod adjustment'),
  ('Handyman Micro', 'Door latch/fix quick repair'),
  ('Handyman Micro', 'Bulb/tube replacement'),
  ('Handyman Micro', 'Shelf mounting (light)'),
  ('Handyman Micro', 'Minor leakage temporary fix'),

  ('Travel Assist', 'Airport drop companion (by your cab)'),
  ('Travel Assist', 'Railway station luggage assist'),
  ('Travel Assist', 'Outstation parcel handover'),
  ('Travel Assist', 'Car/bike pickup from service center'),

  ('Pet Care', 'Dog walk (30 min)'),
  ('Pet Care', 'Pet food pickup'),
  ('Pet Care', 'Pet vet visit companion'),

  ('Documents', 'Government office form submission'),
  ('Documents', 'Photocopy/print and delivery'),
  ('Documents', 'Cheque drop/pickup'),
  ('Documents', 'Agreement witness companion'),

  ('Events', 'Temple/event queue assistance'),
  ('Events', 'Festival shopping assistant'),
  ('Events', 'Return-gift delivery'),

  ('Emergency Assist', 'Urgent medicine run (night)'),
  ('Emergency Assist', 'Immediate blood test sample drop'),
  ('Emergency Assist', 'Hospital report collection'),
  ('Emergency Assist', 'Emergency charger/powerbank delivery')
ON CONFLICT DO NOTHING;
