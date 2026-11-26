/* booter.c — boot a freestanding x86-64 ELF as a libkrun external kernel.
   Serial console (legacy 16550 at 0x3F8) wired to this process's stdin/stdout. */
#include <libkrun.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static bool push_stderr(void *userdata, KrunStr s){
  (void)userdata;
  fwrite(s.data, 1, s.len, stderr);
  return true;
}
static KrunPushStrVtable stderr_vt = KRUN_VTABLE(KrunPushStrVtable, .drop = NULL, .push = push_stderr);
static KrunVtableHandle stderr_w = KRUN_TEMP_OBJ(KRUN_PUSH_STR_TYPE_TAG, &stderr_vt, NULL);

#define CHECK(call) \
    krun_err = NULL; \
    call; \
    if (krun_err) { \
      fprintf(stderr, "%s failed: ", #call); \
      krun_error_message(krun_err, &stderr_w); \
      fputc('\n', stderr); \
      krun_error_destroy(krun_err); \
      return 1; \
    }

int main(int argc, char **argv){
  if (argc < 2) { fprintf(stderr, "usage: %s ELF\n", argv[0]); return 1; }

  KrunError krun_err;
  krun_init_log(-1, KRUN_LOG_LEVEL_OFF, KRUN_LOG_STYLE_AUTO, 0, &krun_err);

  KrunVmmBuilder builder = krun_vmm_builder_new();
  KrunMmioDeviceManager devices = krun_mmio_device_manager_new();
  {
    KrunRngDevice rng = krun_rng_device_new(&krun_err);
    krun_mmio_device_manager_add(devices, rng);
    KrunBalloonDevice balloon = krun_balloon_device_new(&krun_err);
    krun_mmio_device_manager_add(devices, balloon);
  }
  CHECK(krun_vmm_builder_vcpus(&builder, 1, &krun_err));
  CHECK(krun_vmm_builder_ram_mib(&builder, 2048, &krun_err));
  CHECK(KrunPayload payload = krun_payload_load_external(KRUN_STR(argv[1]),
        KRUN_KERNEL_FORMAT_ELF, KRUN_STR(NULL), KRUN_STR(NULL), &krun_err));
  krun_vmm_builder_payload(&builder, payload);
  krun_vmm_builder_devices(&builder, devices);
  CHECK(krun_vmm_builder_add_serial_console(&builder, STDIN_FILENO, STDOUT_FILENO, &krun_err));

  CHECK(KrunVmm vmm = krun_vmm_builder_build(&builder, &krun_err));
  krun_vmm_run(vmm); /* blocking; exits the process with the guest's code */
  return 0;
}
