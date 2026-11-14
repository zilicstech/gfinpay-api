-- PaySprint FD stays off. ZET (SBM, IOB) and GrowMore (DCB) keep the apply URLs already stored on catalog_items.
UPDATE fd_providers
   SET enabled = TRUE, updated_at = now()
 WHERE code IN ('ZET', 'GROWMORE');
