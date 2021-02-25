async function signupHandler(event) {
    event.preventDefault();

    const username = document.querySelector('.username').value;
    const first_name = document.querySelector('.first_name').value;
    const last_name = document.querySelector('.last_name').value;
    const password = document.querySelector('.password').value;
    const errorP = document.querySelector('.error');

    const response = await fetch('/api/users', {
        method: 'POST',
        body: JSON.stringify({
            username,
            first_name,
            last_name,
            password
        }),
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        document.location.replace('/')
    } else {
        response.json().then(text => {
            errorP.append(text.message)
        })
    }
}

document.querySelector('.signup-form').addEventListener('submit', signupHandler);