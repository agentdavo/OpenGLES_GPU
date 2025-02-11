# OpenGLES_GPU

This project implements a production‑ready GPU architecture using SpinalHDL. It targets OpenGL ES 2.0–style rendering using Q16.16 fixed‑point arithmetic while offering advanced compute capabilities. Our design is fully modular and parameterized, incorporating extensive cache and memory optimizations, advanced compute features, and efficient graphics processing.

---

## Features

- **Fixed‑Point Arithmetic (Q16.16):**  
  All numeric computations use 32‑bit fixed‑point math via SpinalHDL’s `SFix` type. Our optimized `FixedPoint` helpers use rounding for multiplication and a shift‑based division method to maximize precision and hardware efficiency.

- **Programmable Shader Pipeline:**  
  A 5‑stage pipelined shader core with a dynamic 16‑register file per core supports an extended opcode set (including DP3, DP4, MAD, SQRT, RSQ, SIN, COS, EXP, LOG, JMP, JZ, JNZ, etc.) to process vertex and fragment data in parallel using a 4‑lane SIMD execution model. Bypass paths for simple operations (e.g., MOV, NEG) reduce latency, and branch instructions update the program counter conditionally.

- **Triangle Rasterization & Early Z‑Testing:**  
  A tile‑based rasterizer computes a triangle’s bounding box using parallel reduction and processes 16×16 tiles. An integrated ZBuffer module performs early Z‑testing to reject occluded fragments before heavy fragment shading, thereby saving computation and memory bandwidth.

- **Texture Sampling & Filtering:**  
  The BilinearTextureSampler module converts normalized texture coordinates into texel data with smooth bilinear filtering. Prefetching and an optional dedicated L1 texture cache reduce redundant texture fetches. Mipmapping (with trilinear filtering) and optional anisotropic filtering further improve texture quality.

- **AXI4‑Lite & Multi‑Channel Bus Integration:**  
  An AXI4‑Lite adapter (with FIFO buffering) converts standard AXI transactions into our internal memory‑mapped bus protocol. Dedicated AXI4 channels for vertex data, framebuffer, and textures—combined with burst‑aligned DMA transfers and deep FIFOs—minimize interface overhead and support out‑of‑order execution.

- **Cache & Memory Subsystem:**  
  - **L1 Cache:** A 16 KB, 16‑way set‑associative, write‑back L1 cache per core with dirty‑bit tracking and simple FIFO (round‑robin) replacement.
  - **L2 Cache:** A shared 16 KB L2 cache with multi‑line prefetching reduces DRAM traffic.
  - **Memory Coalescing:** Burst transfers (aligned to 128 bytes) across SIMD lanes reduce redundant accesses.

- **DMA Engines & Host Memory Interface:**  
  Dedicated DMA engines (GPUDMAEngine) support burst transfers and batching:
  - **VertexDMA:** Batches multiple vertex blocks per DMA request for high‑throughput vertex data fetching.
  - **FrameBufferDMA:** Uses burst transactions and FIFO buffering to efficiently write compressed framebuffer data back to host memory.

- **Shared Memory & Compute Enhancements:**  
  Each shader core includes a 2 KB shared memory (LDS) module that supports atomic operations (add, min, CAS) for efficient inter-thread communication in compute workloads. Our unified shader architecture supports both graphics and compute tasks with register file banking and low‑latency branch handling.

- **Extended Shader Instruction Set:**  
  In addition to standard vector operations, the shader core supports extended math functions (SQRT, RSQ, SIN, COS, EXP, LOG) and branching (JMP, JZ, JNZ) for flexible control flow.

- **Power Efficiency:**  
  Clock gating is implemented (via a `clockEnable` signal) to disable idle blocks and reduce dynamic power consumption.

- **Performance Debugging:**  
  A PerformanceCounter module provides real‑time profiling of instruction counts and DMA transactions.

---

## Design Overview & Reasoning

### Modular & Parameterized Architecture
- **Configuration Parameters:**  
  `GPUConfig` and `ShaderConfig` allow you to parameterize data width, address width, SIMD lanes, cache sizes, and shader program depth—ensuring flexibility and scalability.
  
- **Bundled Data Structures:**  
  Common types such as `VertexData`, `FragmentData`, `BlockVertexData`, `BlockFragmentData`, and `MemoryMapBus` are defined in a single `Bundles` object for clarity and reuse.

### Compute & Graphics Integration
- **Unified Shader Core:**  
  Our 5‑stage pipeline processes multiple SIMD lanes in parallel using `RegNext` for clean stage transitions. The core supports an extended opcode set and branch instructions for advanced control flow.
  
- **Extended ALU Functionality:**  
  A LUT‑based MuxLookup with bypass paths optimizes simple operations (MOV, NEG) and reduces latency for complex functions (SQRT, RSQ, SIN, COS, EXP, LOG).

### Cache & Memory Subsystem
- **L1 & L2 Caches:**  
  The L1 cache is a 16 KB, 16‑way set‑associative, write‑back cache with dirty‑bit tracking and FIFO replacement. A shared 16 KB L2 cache with multi‑line prefetching minimizes DRAM traffic.
  
- **Memory Coalescing:**  
  Burst‑aligned transfers across SIMD lanes and careful load balancing reduce redundant memory accesses.

### Bus Integration & DMA
- **AXI4‑Lite Adapter:**  
  Converts standard AXI transactions into our internal memory‑mapped protocol using FIFO buffering to avoid stalls.
  
- **Multi‑Channel DMA:**  
  Dedicated DMA engines with deep FIFOs and burst transfers enable continuous data flow for vertex data, framebuffer updates, and texture fetches. Batching of DMA requests reduces overhead.

### Rasterization & Texture Filtering
- **Tile‑Based Rasterization & Early Z‑Testing:**  
  The triangle rasterizer uses tile‑based processing and early Z‑testing (via a dedicated ZBuffer) to improve cache locality and discard occluded fragments early.
  
- **Advanced Texture Filtering:**  
  The BilinearTextureSampler performs smooth texture filtering with prefetching; mipmapping and optional anisotropic filtering further enhance texture quality.

---

## Project Structure

The project is contained in a single Scala source file (e.g. `Axi4GPUTopWithDMATopVerilog.scala`) and compiles with SBT. Key sections include:

1. **Global Configurations & Bundles:**  
   Definitions for data formats and configurable parameters.

2. **Fixed‑Point Math & Helper Functions:**  
   Optimized Q16.16 arithmetic (with rounding and efficient bit‑shifting) and extended math operations.

3. **Shader Instruction Set & Pipeline:**  
   A 5‑stage pipeline that processes shader instructions with extended opcodes and branch handling.

4. **Caches & Memory Subsystem:**  
   An enhanced 16 KB L1 cache with write‑back policy and a shared 16 KB L2 cache with prefetching, along with burst‑aligned memory coalescing.

5. **Rasterization, Texture Sampling & Color Modulation:**  
   A tile‑based triangle rasterizer with early Z‑testing, advanced bilinear texture filtering (with mipmapping), and a color modulator.

6. **DMA Engines & AXI4 Adapters:**  
   Dedicated DMA engines (VertexDMA, FrameBufferDMA) and an AXI4‑Lite adapter with FIFO buffering for efficient, burst‑aligned data transfers.

7. **Compute Support:**  
   A unified shader core with shared memory (supporting atomic operations) and register banking for advanced compute workloads.

8. **Top‑Level Integration:**  
   The `GPUTopWithDMA` module integrates all components and interfaces with external memory via separate AXI4 channels (for vertex, framebuffer, and texture data).

9. **Shader Programs:**  
   Example programs (e.g. glFrustum, glTexCoord2f, glMath, glBlend, glBranchZero) demonstrate fixed‑point shader instruction encoding.

10. **Verilog Generation:**  
    The `Axi4GPUTopWithDMATopVerilog` object instantiates the top‑level module and generates synthesizable Verilog RTL.

---

## Compiling and Generating RTL

Ensure you have Scala, SBT, and SpinalHDL installed. To compile the design and generate Verilog RTL, run:

```bash
sbt "runMain gpu.Axi4GPUTopWithDMATopVerilog"