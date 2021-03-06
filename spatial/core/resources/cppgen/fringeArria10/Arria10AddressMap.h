#ifndef __ARRIA10_ADDRESS_MAP_H__
#define __ARRIA10_ADDRESS_MAP_H__

#include <stdio.h>
#include <assert.h>
#include <stdlib.h>
#include <unistd.h>

// Memory mapping related constants
#define FRINGE_MEM_BASEADDR     0x00000000
#define FRINGE_SCALAR_BASEADDR  0xff200000
// Map 4 pages for now
#define MEM_SIZE                0x00004000
#define MAP_LEN                 0x10000
#define FREEZE_BRIDGE_OFFSET    0x00000800


typedef unsigned int u32;


// Bit masks and positions - Command register
#define MREAD(val, mask) (((val) & (mask)) >> __builtin_ctz(mask))
#define MWRITE(val, mask) (((val) << __builtin_ctz(mask)) & (mask))

// Some helper macros
#define EPRINTF(...) fprintf(stderr, __VA_ARGS__)
#define ASSERT(cond, ...) \
  if (!(cond)) { \
    EPRINTF("\n");        \
    EPRINTF(__VA_ARGS__); \
    EPRINTF("\n");        \
    EPRINTF("Assertion (%s) failed in %s, %d\n", #cond, __FILE__, __LINE__); \
    assert(0);  \
  }

#endif
