# Reading for row 343: rounding to odd, the theorem under the AVX2 `L2F` form

Read on 4 November 2026 for row 343 part 343b1 (`VARKA-343.md` 2.3 and 5 step 4): the AVX2 form
of `CAST(l AS FLOAT)` rounds the long to odd in a double and then to nearest in a float, and that
two roundings give the one Java's `(float) l` gives rests on this paper's Theorem 3. The paper was
read in full from the deposit below. **[R]** means read; **[I]** inferred.

| paper | source | licence of the copy |
| :--- | :--- | :--- |
| Boldo, Melquiond: "Emulation of a FMA and correctly-rounded sums: proved algorithms using rounding to odd", IEEE Transactions on Computers 57(4), April 2008, pp. 462-471, DOI 10.1109/TC.2007.70819 | HAL `inria-00080427v2`, submitted 10 November 2010, `https://hal.science/inria-00080427/file/odd-rounding.pdf` (SHA-256 `3ac960acfb9ce987eb9fbc327c315d947d5caed0b461664105471a65dd977720`, 10 pages) | the HAL Authorisation v1 |

**Why there is no transcription.** The deposit carries the HAL Authorisation v1
(`https://about.hal.science/hal-authorisation-v1/`, read 4 November 2026), HAL's default for files
deposited with no licence. Its terms, in the page's French with the accents dropped: the user "peut
lire, consulter, citer (en mentionnant le ou les auteurs et la source) mais doit demander une
autorisation a l'auteur ou a l'ayant droit pour reproduire, adapter ou utiliser l'oeuvre au-dela des
exceptions legales" - read, consult and cite, and ask the author or rights holder before reproducing
it. That does not permit a copy here, so `sql/varka/papers/` holds none (its `README.md`, Licensing)
and these notes and the citation stand in for it. A transcription would need the authors'
permission.

**Which version.** The deposit is the authors' manuscript, not the IEEE typesetting: its running
header is a template's placeholder ("TRANSACTION ON COMPUTERS, VOL. 0, NO. 0, JANUARY 2099"). The
theorem and section numbers below are the manuscript's; the published article is assumed to number
them the same **[I]**, which was not checked against the IEEE copy.

## 1. The theorem the `L2F` form rests on

**Notation [R]** (section II). A format is a precision and a smallest exponent: the extended one is
`B_e = (p + k, E_e)` and the working one `B_w = (p, E_w)`, `2^-E_e` and `2^-E_w` being their
smallest positive numbers. The working rounding is to nearest, ties to even, written as a circle
with the precision as a superscript; rounding to odd at precision `p + k` is written with `p + k`
as a superscript and "odd" as a subscript. Rounding to odd (section III-A) maps a representable
`x` to itself, and any other `x` to whichever of its two neighbours, rounded up or rounded down,
has an odd mantissa.

**Theorem 3 (To_Odd_Even_is_Even) [R]** (section III-C, page 3 of the manuscript), read from the
PDF's word geometry, with the paper's symbols spelled out in ASCII:

> Assuming p >= 2, k >= 2, and E_e >= 2 + E_w,
> for all x in R, round_p( odd_{p+k}(x) ) = round_p(x).

So the precision condition is `k >= 2`: the intermediate rounding to odd keeps at least two bits
more than the final rounding to nearest. The exponent condition `E_e >= 2 + E_w` asks that the
extended format reach at least two binades below the working one; the paper notes it is weaker
than "every normal of `B_w` is normal in `B_e`". It is proven in Coq, special cases included:
subnormals, and a result that is a power of two or the smallest normal (page 4: "seven theorems
and about one thousand lines of Coq"). The paper adds that it holds for any rounding to nearest
whose result depends only on the exact value, ties away from zero among them.

**Why it holds [R]** (the pen-and-paper sketch after the theorem, Figure 4). A value exactly at a
midpoint of two working-precision neighbours is representable at `p + k` bits, so rounding to odd
keeps it and the final rounding sees the true tie. A value near a midpoint but not on it cannot be
rounded to odd onto the midpoint, since with `k >= 2` the midpoint has an even mantissa at `p + k`
bits; it lands on the odd neighbour on its own side, and the final rounding goes the right way. A
value far from a midpoint is unaffected. With `k = 1` the midpoint's last bit at `p + 1` bits is 1,
odd, so a value just off it can round onto it and then tie to even the wrong way: that is the case
`round_to_odd.smt2`'s precision check finds at 25 bits.

**How Varka applies it [I].** For `m = |l|` read unsigned, with `m` in `[2^j, 2^(j+1))` and `j`
from 52 to 63, the form's `t = (m >>> 12) | sticky(m & 0xFFF)` is `m` truncated to its bits from
2^12 up, with the last kept bit or-ed with whether anything was dropped: section III-B's first
implementation of rounding to odd (round toward zero, then or the inexact flag into the last bit),
at precision `j - 11`. Then `t << 12` is exactly a double (at most 52 significant bits), and `D2F`
rounds it to nearest at `p = 24`, so `k = j - 35 >= 17`. The exponents do not bind: the values are
integers of at least 2^52, far from either format's subnormals, and `E_e = 1074 >= 2 + 149`
anyway. Below `2^52` nothing is rounded to odd: the double holds `m` exactly and `D2F` rounds once.
Since `k` differs per binade, `round_to_odd.smt2` states the instance per binade.

## 2. The rest of the paper, briefly

- **Theorem 1 (DblRndStable) [R]**: two successive faithful roundings, to `p + k` then to `p`
  bits with `k >= 0` and `k <= E_e - E_w`, give a faithful rounding of `x`. Double rounding to
  nearest twice is faithful but can be off: `|f - x| <= (1/2 + 2^(-k-1)) ulp(f)`.
- **Theorem 2 (To_Odd*) [R]**: rounding to odd is a rounding mode (total, faithful, monotone), a
  function, and symmetric: `odd(-x) = -odd(x)`. The symmetry is what lets the kernel round the
  magnitude and put the sign back.
- **Section III-B [R]**: two hardware implementations - round toward zero then or the sticky bit
  into the mantissa's last bit, or round toward zero at `p + k - 1` bits and append the inexact bit
  - and a pointer to a software one in V-D.
- **Section IV [R]**: Algorithm 1 emulates an FMA from a double-precision multiply split by Dekker,
  an error-free addition and one addition rounded to odd; Theorem 4 (FmaEmul) proves it for
  `p >= 5` when the multiplication's error term is representable. Lemma 1 (AddOddEven) is the step.
- **Section V [R]**: Algorithm 2, an iterated sum in extended precision rounded to odd, correctly
  rounded under ordering conditions (Theorem 5, Summation); Algorithm 3, the correctly rounded sum
  of three numbers; and V-D's use case, CRlibm's logarithm, whose Listing 1 rounds to odd in
  software by adjusting the bits of `th` by one where the error-free adder's `tl` is non-zero and
  `th`'s mantissa is even.
- **Not used by Varka**: the FMA emulation and the sums. Row 343 needs only Theorem 3, Theorem 2's
  symmetry, and III-B's truncate-and-or construction.
