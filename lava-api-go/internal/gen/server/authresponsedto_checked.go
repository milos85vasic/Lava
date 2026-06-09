package server

// Discriminator-honoring accessor for the AuthResponseDto discriminated union.
//
// The oapi-codegen-generated As* accessors in api.gen.go (DO NOT EDIT) do a
// blind json.Unmarshal of the raw union bytes into the target struct and never
// consult the "type" discriminator. AuthResponseDtoSuccess embeds a non-pointer
// UserDto, so unmarshalling a non-Success variant (WrongCredits / CaptchaRequired
// / ServiceUnavailable) — whose wire bytes carry no "user" key, or carry a stray
// one on a cross-backend payload — into AuthResponseDtoSuccess SUCCEEDS with NO
// error. The User field is simply left zero-valued (or populated from the stray
// key). A caller that relies on the returned error to detect "not Success"
// therefore silently accepts a non-Success response as a successful login
// (forensic anchor: LVA-046, same type-confusion class as LVA-032). The
// generated code cannot be edited (CI enforces "regenerate produces empty diff"),
// so the fix lives here: the *Checked accessor reads Discriminator() first and
// refuses to map a union whose discriminator does not name "Success".

// AsAuthResponseDtoSuccessChecked returns the union as an AuthResponseDtoSuccess
// only when the discriminator is "Success". On a discriminator mismatch it
// returns a zero value and *ErrDiscriminatorMismatch. On a structurally invalid
// union it returns the underlying decode error. This is the discriminator-
// honoring counterpart to the generated AsAuthResponseDtoSuccess.
func (t AuthResponseDto) AsAuthResponseDtoSuccessChecked() (AuthResponseDtoSuccess, error) {
	disc, err := t.Discriminator()
	if err != nil {
		return AuthResponseDtoSuccess{}, err
	}
	if disc != string(Success) {
		return AuthResponseDtoSuccess{}, &ErrDiscriminatorMismatch{Want: string(Success), Got: disc}
	}
	return t.AsAuthResponseDtoSuccess()
}
