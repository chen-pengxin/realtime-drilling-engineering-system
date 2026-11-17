-- Replanning geometry is owned by the supplied well_control_iscwsa profile.
-- Keep project constraints editable, while removing legacy UI preset knobs.
UPDATE drilling_project_config
   SET algorithm_defaults_json=JSON_SET(
     JSON_REMOVE(COALESCE(algorithm_defaults_json,JSON_OBJECT()),
       '$.planning.nativeOptions.scenario_inclination_change_deg',
       '$.planning.nativeOptions.scenario_azimuth_change_deg',
       '$.planning.segmentCount'),
     '$.planning.nativeOptions.trajectory_mode','arc-straight',
     '$.planning.nativeOptions.n_segments',COALESCE(JSON_EXTRACT(algorithm_defaults_json,'$.planning.segmentCount'),3)
   )
 WHERE algorithm_defaults_json IS NOT NULL;
