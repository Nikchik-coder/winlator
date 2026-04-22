INSERT INTO public.app_versions (
  version_code,
  version_name,
  download_url,
  release_notes,
  is_mandatory
) VALUES (
  7,
  '1.0.6',
  '<PASTE_APK_URL_HERE>',
  '1.0.6: FlatOut 2 workaround + runtime test matrix. Adds BOX64_DYNAREC_LOG=2 trace-to-file; keeps winxp + dsound=b; adds dxwrapperConfig support + DXVK 1.10.3 option.',
  FALSE
);